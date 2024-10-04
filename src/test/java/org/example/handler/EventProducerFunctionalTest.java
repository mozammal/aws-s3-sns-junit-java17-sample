package org.example.handler;

import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.serialization.PojoSerializer;
import com.amazonaws.services.lambda.runtime.serialization.events.LambdaEventSerializers;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.sns.AmazonSNS;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

@ExtendWith(SystemStubsExtension.class)
public class EventProducerFunctionalTest {
  @SystemStub
  private final EnvironmentVariables variables =
      new EnvironmentVariables("FAN_OUT_TOPIC", "fake_sns_topic");

  @Test
  public void handleRequest_whenS3ValidEventsTriggered_publishToSNS_successfully() {
    AmazonSNS mockSNS = Mockito.mock(AmazonSNS.class);
    AmazonS3 mockS3 = Mockito.mock(AmazonS3.class);
    final PojoSerializer<S3Event> s3EventSerializer =
        LambdaEventSerializers.serializerFor(S3Event.class, ClassLoader.getSystemClassLoader());

    S3Event s3Event = s3EventSerializer.fromJson(getClass().getResourceAsStream("/s3_event.json"));
    String bucket = s3Event.getRecords().get(0).getS3().getBucket().getName();
    String key = s3Event.getRecords().get(0).getS3().getObject().getKey();

    S3Object s3Object = new S3Object();
    s3Object.setObjectContent(getClass().getResourceAsStream(String.format("/%s", key)));
    Mockito.when(mockS3.getObject(bucket, key)).thenReturn(s3Object);

    var eventProducer = new EventProducer(mockSNS, mockS3);
    eventProducer.handleRequest(s3Event);
    String fanOutTopic = System.getenv("FAN_OUT_TOPIC");

    ArgumentCaptor<String> topics = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
    Mockito.verify(mockSNS, Mockito.times(3)).publish(topics.capture(), messages.capture());
    assertArrayEquals(
        new String[] {fanOutTopic, fanOutTopic, fanOutTopic}, topics.getAllValues().toArray());
    assertArrayEquals(
        new String[] {
          "{\"locationName\":\"Brooklyn, NY\",\"temperature\":91.0,\"timestamp\":1564428897,\"longitude\":-73.99,\"latitude\":40.7}",
          "{\"locationName\":\"Oxford, UK\",\"temperature\":64.0,\"timestamp\":1564428898,\"longitude\":-1.25,\"latitude\":51.75}",
          "{\"locationName\":\"Charlottesville, VA\",\"temperature\":87.0,\"timestamp\":1564428899,\"longitude\":-78.47,\"latitude\":38.02}"
        },
        messages.getAllValues().toArray());
  }

  @Test
  public void handleRequest_whenS3InvalidEventsTriggered_throws_exception() {
    AmazonSNS mockSNS = Mockito.mock(AmazonSNS.class);
    AmazonS3 mockS3 = Mockito.mock(AmazonS3.class);
    final PojoSerializer<S3Event> s3EventSerializer =
        LambdaEventSerializers.serializerFor(S3Event.class, ClassLoader.getSystemClassLoader());

    S3Event s3Event =
        s3EventSerializer.fromJson(getClass().getResourceAsStream("/s3_event_bad_data.json"));
    String bucket = s3Event.getRecords().get(0).getS3().getBucket().getName();
    String key = s3Event.getRecords().get(0).getS3().getObject().getKey();

    S3Object s3Object = new S3Object();
    s3Object.setObjectContent(getClass().getResourceAsStream(String.format("/%s", key)));
    Mockito.when(mockS3.getObject(bucket, key)).thenReturn(s3Object);

    var eventProducer = new EventProducer(mockSNS, mockS3);
    Assertions.assertThrows(RuntimeException.class, () -> eventProducer.handleRequest(s3Event));
  }

  @Test
  public void whenSNSTopic_missing_throws_exception() {
    AmazonSNS mockSNS = Mockito.mock(AmazonSNS.class);
    AmazonS3 mockS3 = Mockito.mock(AmazonS3.class);

    variables.set("FAN_OUT_TOPIC", null);

    Assertions.assertThrows(RuntimeException.class, () -> new EventProducer(mockSNS, mockS3));
  }
}
