package org.example.handler;

import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.sns.AmazonSNS;
import com.amazonaws.services.sns.AmazonSNSClientBuilder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.entity.WeatherEvent;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public final class EventProducer {
  private static final Logger logger = LogManager.getLogger(EventProducer.class);
  static String FAN_OUT_TOPIC = "FAN_OUT_TOPIC";
  private final AmazonS3 s3Client;
  private final AmazonSNS snsClient;
  private final String snsTopic;
  private final ObjectMapper objectMapper =
      new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  public EventProducer() {
    this(AmazonSNSClientBuilder.defaultClient(), AmazonS3ClientBuilder.defaultClient());
  }

  public EventProducer(AmazonSNS snsClient, AmazonS3 s3Client) {
    this.snsClient = snsClient;
    this.s3Client = s3Client;
    this.snsTopic = System.getenv(FAN_OUT_TOPIC);

    if (this.snsTopic == null) {
      throw new RuntimeException(String.format("%s is missing", FAN_OUT_TOPIC));
    }
  }

  public void handleRequest(S3Event s3Event) {
    List<WeatherEvent> events =
        s3Event.getRecords().stream()
            .map(this::getObjectFromS3Bucket)
            .map(this::getWeatherEvents)
            .flatMap(List::stream)
            .toList();

    events.stream().map(this::eventToString).forEach(this::publishToSNS);

    System.out.println("Published " + events.size() + " weather events to SNS");
  }

  List<WeatherEvent> getWeatherEvents(InputStream input) {
    try (InputStream is = input) {
      return List.of(objectMapper.readValue(is, WeatherEvent[].class));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private InputStream getObjectFromS3Bucket(S3EventNotification.S3EventNotificationRecord record) {
    String bucket = record.getS3().getBucket().getName();
    String key = record.getS3().getObject().getKey();
    return s3Client.getObject(bucket, key).getObjectContent();
  }

  private void publishToSNS(String message) {
    logger.info("Sending event to SNS");
    snsClient.publish(snsTopic, message);
  }

  String eventToString(WeatherEvent weatherEvent) {
    try {
      return objectMapper.writeValueAsString(weatherEvent);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }
}
