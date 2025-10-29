package io.armadaproject.jenkins.plugin;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Event manager for subscribing to and publishing Armada job events. Uses a pub/sub pattern where
 * subscribers can register for events on specific job set IDs.
 *
 * @param <T> the type of event to manage (e.g., JobRunningEvent)
 */
public class ArmadaEventManager<T> {

  private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<T>>> subscribers =
      new ConcurrentHashMap<>();

  /**
   * Subscribe to events for a specific job set ID.
   *
   * @param jobSetId   the job set ID to subscribe to
   * @param subscriber the consumer that will be called when an event is published
   */
  public void subscribe(String jobSetId, Consumer<T> subscriber) {
    subscribers.computeIfAbsent(jobSetId, k -> new CopyOnWriteArrayList<>()).add(subscriber);
  }

  /**
   * Unsubscribe from events for a specific job set ID.
   *
   * @param jobSetId   the job set ID to unsubscribe from
   * @param subscriber the consumer to remove
   */
  public void unsubscribe(String jobSetId, Consumer<T> subscriber) {
    CopyOnWriteArrayList<Consumer<T>> consumerList = subscribers.get(jobSetId);
    if (consumerList != null) {
      consumerList.remove(subscriber);
      if (consumerList.isEmpty()) {
        subscribers.remove(jobSetId);
      }
    }
  }

  /**
   * Publish an event to all subscribers for a specific job set ID.
   *
   * @param jobSetId the job set ID to publish to
   * @param event    the event to publish
   */
  public void publish(String jobSetId, T event) {
    CopyOnWriteArrayList<Consumer<T>> consumerList = subscribers.get(jobSetId);
    if (consumerList != null) {
      for (Consumer<T> subscriber : consumerList) {
        subscriber.accept(event);
      }
    }
  }

}
