package io.sentry.transport;

import io.sentry.ILogger;
import io.sentry.RequestDetails;
import io.sentry.SentryEnvelope;
import io.sentry.SentryLevel;
import io.sentry.SentryOptions;
import io.sentry.cache.IEnvelopeCache;
import io.sentry.hints.Cached;
import io.sentry.hints.DiskFlushNotification;
import io.sentry.hints.Retryable;
import io.sentry.hints.SubmissionResult;
import io.sentry.util.LogUtils;
import io.sentry.util.Objects;
import java.io.IOException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@link ITransport} implementation that executes request asynchronously in a blocking manner using
 * {@link java.net.HttpURLConnection}.
 */
public final class AsyncHttpTransport implements ITransport {

  private final @NotNull QueuedThreadPoolExecutor executor;
  private final @NotNull IEnvelopeCache envelopeCache;
  private final @NotNull SentryOptions options;
  private final @NotNull RateLimiter rateLimiter;
  private final @NotNull ITransportGate transportGate;
  private final @NotNull HttpConnection connection;

  public AsyncHttpTransport(
      final @NotNull SentryOptions options,
      final @NotNull RateLimiter rateLimiter,
      final @NotNull ITransportGate transportGate,
      final @NotNull RequestDetails requestDetails) {
    this(
        initExecutor(
            options.getMaxQueueSize(), options.getEnvelopeDiskCache(), options.getLogger()),
        options,
        rateLimiter,
        transportGate,
        new HttpConnection(options, requestDetails, rateLimiter));
  }

  public AsyncHttpTransport(
      final @NotNull QueuedThreadPoolExecutor executor,
      final @NotNull SentryOptions options,
      final @NotNull RateLimiter rateLimiter,
      final @NotNull ITransportGate transportGate,
      final @NotNull HttpConnection httpConnection) {
    this.executor = Objects.requireNonNull(executor, "executor is required");
    this.envelopeCache =
        Objects.requireNonNull(options.getEnvelopeDiskCache(), "envelopeCache is required");
    this.options = Objects.requireNonNull(options, "options is required");
    this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter is required");
    this.transportGate = Objects.requireNonNull(transportGate, "transportGate is required");
    this.connection = Objects.requireNonNull(httpConnection, "httpConnection is required");
  }

  @Override
  @SuppressWarnings("FutureReturnValueIgnored")
  public void send(final @NotNull SentryEnvelope envelope, final @Nullable Object hint)
      throws IOException {
    // For now no caching on envelopes
    IEnvelopeCache currentEnvelopeCache = envelopeCache;
    boolean cached = false;
    if (hint instanceof Cached) {
      currentEnvelopeCache = NoOpEnvelopeCache.getInstance();
      cached = true;
      options.getLogger().log(SentryLevel.DEBUG, "Captured Envelope is already cached");
    }

    final SentryEnvelope filteredEnvelope = rateLimiter.filter(envelope, hint);

    if (filteredEnvelope == null) {
      if (cached) {
        envelopeCache.discard(envelope);
      }
    } else {
      executor.submit(new EnvelopeSender(filteredEnvelope, hint, currentEnvelopeCache));
    }
  }

  @Override
  public void flush(long timeoutMillis) {
    executor.waitTillIdle(timeoutMillis);
  }

  private static QueuedThreadPoolExecutor initExecutor(
      final int maxQueueSize,
      final @NotNull IEnvelopeCache envelopeCache,
      final @NotNull ILogger logger) {

    final RejectedExecutionHandler storeEvents =
        (r, executor) -> {
          if (r instanceof EnvelopeSender) {
            final EnvelopeSender envelopeSender = (EnvelopeSender) r;

            if (!(envelopeSender.hint instanceof Cached)) {
              envelopeCache.store(envelopeSender.envelope, envelopeSender.hint);
            }

            markHintWhenSendingFailed(envelopeSender.hint, true);
            logger.log(SentryLevel.WARNING, "Envelope rejected");
          }
        };

    return new QueuedThreadPoolExecutor(
        1, maxQueueSize, new AsyncConnectionThreadFactory(), storeEvents, logger);
  }

  @Override
  public void close() throws IOException {
    executor.shutdown();
    options.getLogger().log(SentryLevel.DEBUG, "Shutting down");
    try {
      if (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
        options
            .getLogger()
            .log(
                SentryLevel.WARNING,
                "Failed to shutdown the async connection async sender within 1 minute. Trying to force it now.");
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      // ok, just give up then...
      options
          .getLogger()
          .log(SentryLevel.DEBUG, "Thread interrupted while closing the connection.");
      Thread.currentThread().interrupt();
    }
  }

  /**
   * It marks the hints when sending has failed, so it's not necessary to wait the timeout
   *
   * @param hint the Hint
   * @param retry if event should be retried or not
   */
  private static void markHintWhenSendingFailed(final @Nullable Object hint, final boolean retry) {
    if (hint instanceof SubmissionResult) {
      ((SubmissionResult) hint).setResult(false);
    }
    if (hint instanceof Retryable) {
      ((Retryable) hint).setRetry(retry);
    }
  }

  private static final class AsyncConnectionThreadFactory implements ThreadFactory {
    private int cnt;

    @Override
    public @NotNull Thread newThread(final @NotNull Runnable r) {
      final Thread ret = new Thread(r, "SentryAsyncConnection-" + cnt++);
      ret.setDaemon(true);
      return ret;
    }
  }

  private final class EnvelopeSender implements Runnable {
    private final @NotNull SentryEnvelope envelope;
    private final @Nullable Object hint;
    private final @NotNull IEnvelopeCache envelopeCache;
    private final TransportResult failedResult = TransportResult.error();

    EnvelopeSender(
        final @NotNull SentryEnvelope envelope,
        final @Nullable Object hint,
        final @NotNull IEnvelopeCache envelopeCache) {
      this.envelope = Objects.requireNonNull(envelope, "Envelope is required.");
      this.hint = hint;
      this.envelopeCache = Objects.requireNonNull(envelopeCache, "EnvelopeCache is required.");
    }

    @Override
    public void run() {
      TransportResult result = this.failedResult;
      try {
        result = flush();
        options.getLogger().log(SentryLevel.DEBUG, "Envelope flushed");
      } catch (Throwable e) {
        options.getLogger().log(SentryLevel.ERROR, e, "Envelope submission failed");
        throw e;
      } finally {
        if (hint instanceof SubmissionResult) {
          options
              .getLogger()
              .log(SentryLevel.DEBUG, "Marking envelope submission result: %s", result.isSuccess());
          ((SubmissionResult) hint).setResult(result.isSuccess());
        }
      }
    }

    private @NotNull TransportResult flush() {
      TransportResult result = this.failedResult;

      envelopeCache.store(envelope, hint);

      if (hint instanceof DiskFlushNotification) {
        ((DiskFlushNotification) hint).markFlushed();
        options.getLogger().log(SentryLevel.DEBUG, "Disk flush envelope fired");
      }

      if (transportGate.isConnected()) {
        try {
          result = connection.send(envelope);
          if (result.isSuccess()) {
            envelopeCache.discard(envelope);
          } else {
            final String message =
                "The transport failed to send the envelope with response code "
                    + result.getResponseCode();

            options.getLogger().log(SentryLevel.ERROR, message);

            throw new IllegalStateException(message);
          }
        } catch (IOException e) {
          // Failure due to IO is allowed to retry the event
          if (hint instanceof Retryable) {
            ((Retryable) hint).setRetry(true);
          } else {
            LogUtils.logIfNotRetryable(options.getLogger(), hint);
          }
          throw new IllegalStateException("Sending the event failed.", e);
        }
      } else {
        // If transportGate is blocking from sending, allowed to retry
        if (hint instanceof Retryable) {
          ((Retryable) hint).setRetry(true);
        } else {
          LogUtils.logIfNotRetryable(options.getLogger(), hint);
        }
      }
      return result;
    }
  }
}
