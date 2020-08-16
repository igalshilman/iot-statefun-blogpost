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
import org.apache.flink.statefun.sdk.state.PersistedTable;

import io.cloudfun.domain.RackHealthModel;
import io.cloudfun.generated.Incident;
import io.cloudfun.generated.Ok;
import io.cloudfun.generated.PerServerOpenIncidents;
import io.cloudfun.generated.RackAlert;
import io.cloudfun.generated.ServerAlert;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

public final class RackFun implements StatefulFunction {

  public static final FunctionType Type = new FunctionType("io.cloudfun", "rack");

  /**
   * a Map between an incident type (a numeric value) to a count of how many servers experiencing
   * that particular incident.
   */
  @Persisted
  private final PersistedTable<Integer, Integer> incidentsByType =
      PersistedTable.of("incidents-by-type", Integer.class, Integer.class);

  /** a Mapping between a server id and the type of open incidents on that server */
  @Persisted
  private final PersistedTable<String, PerServerOpenIncidents> incidentsByServer =
      PersistedTable.of("incidents-by-server", String.class, PerServerOpenIncidents.class);

  private static PerServerOpenIncidents mergeNewIncidentsWithExisting(
      @Nullable PerServerOpenIncidents openIncidentsPerServer, List<Integer> newIncidentTypes) {
    if (openIncidentsPerServer == null) {
      return PerServerOpenIncidents.newBuilder().addAllType(newIncidentTypes).build();
    }
    Set<Integer> existing = new LinkedHashSet<>(openIncidentsPerServer.getTypeList());
    existing.addAll(newIncidentTypes);
    return openIncidentsPerServer.toBuilder().addAllType(existing).build();
  }

  private static List<Integer> extractIncidentTypesFromAlert(ServerAlert serverAlert) {
    return serverAlert.getIncidentList().stream()
        .map(Incident::getType)
        .collect(Collectors.toList());
  }

  @Override
  public void invoke(Context context, Object message) {
    if (message instanceof ServerAlert) {
      onServerAlert(context, (ServerAlert) message);
    } else if (message instanceof Ok) {
      onServerOk(context, (Ok) message);
    } else {
      throw new IllegalStateException("Unknown message type " + message);
    }
  }

  private void onServerAlert(Context context, ServerAlert serverAlert) {
    List<Integer> incidentTypes = extractIncidentTypesFromAlert(serverAlert);
    // update incidents by type view
    for (int incidentType : incidentTypes) {
      @Nullable Integer count = incidentsByType.get(incidentType);
      incidentsByType.set(incidentType, count == null ? 1 : count + 1);
    }
    // update incidents per server
    PerServerOpenIncidents openIncidentsPerServer =
        incidentsByServer.get(serverAlert.getServerId());

    incidentsByServer.set(
        serverAlert.getServerId(),
        mergeNewIncidentsWithExisting(openIncidentsPerServer, incidentTypes));

    // check if there are correlated incidents.
    Optional<RackAlert> alert = RackHealthModel.tryCorrelatedIncidents(incidentsByType.entries());
    if (!alert.isPresent()) {
      return;
    }
    String dataCenterId = Ids.parent(serverAlert.getRackId());
    context.send(DataCenterFun.Type, dataCenterId, alert.get());
  }

  private void onServerOk(Context context, @SuppressWarnings("unused") Ok ok) {
    String serverId = context.caller().id();
    // update incidents by type view
    PerServerOpenIncidents openIncidentsPerServer = incidentsByServer.get(serverId);
    for (int incidentType : openIncidentsPerServer.getTypeList()) {
      final int count = incidentsByType.get(incidentType);
      if (count == 1) {
        incidentsByType.remove(incidentType);
      } else {
        incidentsByType.set(incidentType, count - 1);
      }
    }
    // update the incidents by server view
    incidentsByServer.remove(serverId);
    Optional<RackAlert> alert = RackHealthModel.tryCorrelatedIncidents(incidentsByType.entries());
    // check if there are correlated incidents.
    if (!alert.isPresent()) {
      final String rackId = context.self().id();
      final String dataCenterId = Ids.parent(rackId);
      context.send(DataCenterFun.Type, dataCenterId, Ok.getDefaultInstance());
    }
  }
}
