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

import org.apache.flink.statefun.sdk.Context;
import org.apache.flink.statefun.sdk.FunctionType;
import org.apache.flink.statefun.sdk.StatefulFunction;
import org.apache.flink.statefun.sdk.annotations.Persisted;
import org.apache.flink.statefun.sdk.state.PersistedAppendingBuffer;
import org.apache.flink.statefun.sdk.state.PersistedValue;

import io.cloudfun.domain.ServerHealthModel;
import io.cloudfun.domain.ServerHealthModel.ServerHealth;
import io.cloudfun.generated.CommissionServer;
import io.cloudfun.generated.DecommissionServer;
import io.cloudfun.generated.Incident;
import io.cloudfun.generated.Ok;
import io.cloudfun.generated.ServerAlert;
import io.cloudfun.generated.ServerMetricReport;
import io.cloudfun.generated.ServerState;
import io.cloudfun.generated.ServerState.Status;
import io.cloudfun.generated.Timer;

import java.time.Duration;

import static org.apache.flink.statefun.sdk.state.Expiration.expireAfterWriting;

public final class ServerFun implements StatefulFunction {

  public static final FunctionType Type = new FunctionType("io.cloudfun", "server-fun");

  private static final Duration MAX_MISSING_REPORT_INTERVAL = Duration.ofMinutes(1);

  @Persisted
  private final PersistedAppendingBuffer<ServerMetricReport> metricHistory =
      PersistedAppendingBuffer.of(
          "history", ServerMetricReport.class, expireAfterWriting(Duration.ofMinutes(15)));

  @Persisted
  private final PersistedValue<ServerState> serverHealthState =
      PersistedValue.of("server-health-state", ServerState.class);

  private static void registerNextTimer(Context context) {
    context.sendAfter(MAX_MISSING_REPORT_INTERVAL, context.self(), Timer.getDefaultInstance());
  }

  private static boolean areReportMissingForTooLong(ServerState serverState) {
    final Duration elapsed =
        Duration.ofMillis(System.currentTimeMillis() - serverState.getLastReported());

    return elapsed.compareTo(MAX_MISSING_REPORT_INTERVAL) > 0;
  }

  @Override
  public void invoke(Context context, Object message) {
    if (message instanceof CommissionServer) {
      onCommissionServer(context, (CommissionServer) message);
    } else if (message instanceof DecommissionServer) {
      onDecommissionServer(context);
    } else if (message instanceof ServerMetricReport) {
      onServerMetricReport(context, (ServerMetricReport) message);
    } else if (message instanceof Timer) {
      onTimer(context);
    } else {
      throw new IllegalArgumentException("Unrecognized message type " + message);
    }
  }

  private void onCommissionServer(Context context, CommissionServer message) {
    serverHealthState.set(
        ServerState.newBuilder()
            .setServerId(message.getServerId())
            .setRackId(message.getRackId())
            .setLastReported(System.currentTimeMillis())
            .setStatus(Status.UNKNOWN)
            .build());

    registerNextTimer(context);
  }

  private void onDecommissionServer(@SuppressWarnings("unused") Context context) {
    serverHealthState.clear();
    metricHistory.clear();
  }

  private void onTimer(Context context) {
    ServerState serverState = serverHealthState.get();
    if (serverState == null) {
      // This server was decommissioned before this timer was delivered.
      // do nothing.
      return;
    }
    if (areReportMissingForTooLong(serverState)) {
      final String serverId = serverState.getServerId();
      final String rackId = serverState.getRackId();

      ServerAlert alert =
          ServerAlert.newBuilder()
              .setServerId(serverId)
              .setRackId(rackId)
              .addIncident(
                  Incident.newBuilder()
                      .setSeverity(1)
                      .setType(Constants.MISSING_SERVER_METRIC_REPORT)
                      .setContext("Missing metric report"))
              .build();

      context.send(RackFun.Type, rackId, alert);
      context.send(Constants.SERVER_ALERT_EGRESS, alert);

      serverHealthState.set(serverState.toBuilder().setStatus(Status.UNKNOWN).build());
    }

    registerNextTimer(context);
  }

  private void onServerMetricReport(Context context, ServerMetricReport currentReport) {
    ServerState serverState = serverHealthState.get();
    if (serverState == null) {
      // A server is reporting metrics, before we have received a CommissionServer event,
      // or after we've got a DecommissionServer event.
      return;
    }
    final Status lastKnownStatus = serverState.getStatus();
    final ServerHealth serverHealth =
        ServerHealthModel.evaluate(lastKnownStatus, metricHistory.view(), currentReport);

    metricHistory.append(currentReport);

    serverHealthState.set(
        ServerState.newBuilder()
            .setStatus(serverHealth.getStatus())
            .setLastReported(System.currentTimeMillis())
            .build());

    if (lastKnownStatus == serverHealth.getStatus()) {
      // Nothing was changed since the last report. So we just update the lastReportedTime.
      serverHealthState.set(
          serverState.toBuilder().setLastReported(System.currentTimeMillis()).build());
      return;
    }

    final String serverId = serverState.getServerId();
    final String containingRackId = serverState.getRackId();

    if (serverHealth.getStatus() != Status.OK) {
      ServerAlert alert =
          ServerAlert.newBuilder()
              .setServerId(serverId)
              .setRackId(containingRackId)
              .addAllIncident(serverHealth.getIncidents())
              .build();
      context.send(Constants.SERVER_ALERT_EGRESS, alert);
      context.send(RackFun.Type, containingRackId, alert);
    } else {
      // notify our rack that this server is back to normal.
      context.send(RackFun.Type, containingRackId, Ok.getDefaultInstance());
    }
  }
}
