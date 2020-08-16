/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.cloudfun;

import org.apache.flink.statefun.sdk.kafka.KafkaEgressBuilder;
import org.apache.flink.statefun.sdk.kafka.KafkaEgressSerializer;
import org.apache.flink.statefun.sdk.kafka.KafkaIngressBuilder;
import org.apache.flink.statefun.sdk.kafka.KafkaIngressDeserializer;
import org.apache.flink.statefun.sdk.spi.StatefulFunctionModule;

import com.google.protobuf.InvalidProtocolBufferException;
import io.cloudfun.generated.CommissionServer;
import io.cloudfun.generated.DecommissionServer;
import io.cloudfun.generated.GetUnhealthyRacks;
import io.cloudfun.generated.ServerMetricReport;
import io.cloudfun.generated.UnhealthyRacks;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class Module implements StatefulFunctionModule {

  @Override
  public void configure(Map<String, String> globalConfiguration, Binder binder) {
    String kafkaBrokerAddress =
        globalConfiguration.getOrDefault(
            Constants.KAFKA_BOOTSTRAP_SERVERS_CONF, "kafka-broker:9092");

    // ingress CommissionServer

    binder.bindIngress(
        KafkaIngressBuilder.forIdentifier(Constants.COMMISSION_SERVER_INGRESS)
            .withTopic("commission_server")
            .withKafkaAddress(kafkaBrokerAddress)
            .withDeserializer(CommissionServerDeserializer.class)
            .withProperty(ConsumerConfig.GROUP_ID_CONFIG, "statefun")
            .build());

    binder.bindIngressRouter(
        Constants.COMMISSION_SERVER_INGRESS,
        (msg, downstream) -> downstream.forward(ServerFun.Type, msg.getServerId(), msg));

    // ingress DecommissionServer

    binder.bindIngress(
        KafkaIngressBuilder.forIdentifier(Constants.DECOMMISSION_SERVER_INGRESS)
            .withTopic("decommission_server")
            .withKafkaAddress(kafkaBrokerAddress)
            .withDeserializer(DeCommissionServerDeserializer.class)
            .withProperty(ConsumerConfig.GROUP_ID_CONFIG, "statefun")
            .build());

    binder.bindIngressRouter(
        Constants.DECOMMISSION_SERVER_INGRESS,
        (msg, downstream) -> downstream.forward(ServerFun.Type, msg.getServerId(), msg));

    // ingress ServerMetrics

    binder.bindIngress(
        KafkaIngressBuilder.forIdentifier(Constants.SERVER_METRICS_INGRESS)
            .withTopic("server_metrics")
            .withKafkaAddress(kafkaBrokerAddress)
            .withDeserializer(ServerMetricReportDeserializer.class)
            .withProperty(ConsumerConfig.GROUP_ID_CONFIG, "statefun")
            .build());

    binder.bindIngressRouter(
        Constants.SERVER_METRICS_INGRESS,
        (msg, downstream) -> downstream.forward(ServerFun.Type, msg.getServerId(), msg));

    // ingress GetUnhealthyRacks

    binder.bindIngress(
        KafkaIngressBuilder.forIdentifier(Constants.GET_UNHEALTHY_RACKS)
            .withTopic("get_unhealthy_racks")
            .withKafkaAddress(kafkaBrokerAddress)
            .withDeserializer(GetUnhealthyRacksDeserializer.class)
            .withProperty(ConsumerConfig.GROUP_ID_CONFIG, "statefun")
            .build());

    binder.bindIngressRouter(
        Constants.GET_UNHEALTHY_RACKS,
        (msg, downstream) -> downstream.forward(DataCenterFun.Type, msg.getDataCenter(), msg));

    // egress UnhealthyRacks

    binder.bindEgress(
        KafkaEgressBuilder.forIdentifier(Constants.UNHEALTHY_RACKS_EGRESS)
            .withKafkaAddress(kafkaBrokerAddress)
            .withSerializer(UnhealthyRacksSerializer.class)
            .build());
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Kafka Serializers
  // ---------------------------------------------------------------------------------------------------------------

  private static final class CommissionServerDeserializer
      implements KafkaIngressDeserializer<CommissionServer> {

    @Override
    public CommissionServer deserialize(ConsumerRecord<byte[], byte[]> consumerRecord) {
      try {
        return CommissionServer.parseFrom(consumerRecord.value());
      } catch (InvalidProtocolBufferException e) {
        throw new IllegalStateException("Unparsable protobuf message", e);
      }
    }
  }

  private static final class DeCommissionServerDeserializer
      implements KafkaIngressDeserializer<DecommissionServer> {

    @Override
    public DecommissionServer deserialize(ConsumerRecord<byte[], byte[]> consumerRecord) {
      try {
        return DecommissionServer.parseFrom(consumerRecord.value());
      } catch (InvalidProtocolBufferException e) {
        throw new IllegalStateException("Unparsable protobuf message", e);
      }
    }
  }

  private static final class ServerMetricReportDeserializer
      implements KafkaIngressDeserializer<ServerMetricReport> {

    @Override
    public ServerMetricReport deserialize(ConsumerRecord<byte[], byte[]> consumerRecord) {
      try {
        return ServerMetricReport.parseFrom(consumerRecord.value());
      } catch (InvalidProtocolBufferException e) {
        throw new IllegalStateException("Unparsable protobuf message", e);
      }
    }
  }

  private static final class GetUnhealthyRacksDeserializer
      implements KafkaIngressDeserializer<GetUnhealthyRacks> {

    @Override
    public GetUnhealthyRacks deserialize(ConsumerRecord<byte[], byte[]> consumerRecord) {
      try {
        return GetUnhealthyRacks.parseFrom(consumerRecord.value());
      } catch (InvalidProtocolBufferException e) {
        throw new IllegalStateException("Unparsable protobuf message", e);
      }
    }
  }

  private static final class UnhealthyRacksSerializer
      implements KafkaEgressSerializer<UnhealthyRacks> {

    @Override
    public ProducerRecord<byte[], byte[]> serialize(UnhealthyRacks fromFn) {
      byte[] key = fromFn.getDataCenter().getBytes(StandardCharsets.UTF_8);
      byte[] value = fromFn.toByteArray();
      return new ProducerRecord<>("unhealthy_racks", key, value);
    }
  }
}
