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

import org.apache.flink.statefun.sdk.io.EgressIdentifier;
import org.apache.flink.statefun.sdk.io.IngressIdentifier;

import io.cloudfun.generated.CommissionServer;
import io.cloudfun.generated.DataCenterAlert;
import io.cloudfun.generated.DecommissionServer;
import io.cloudfun.generated.GetUnhealthyRacks;
import io.cloudfun.generated.ServerAlert;
import io.cloudfun.generated.ServerMetricReport;
import io.cloudfun.generated.UnhealthyRacks;

public class Constants {

  public static final String KAFKA_BOOTSTRAP_SERVERS_CONF = "kafka";

  static final int MISSING_SERVER_METRIC_REPORT = 1;

  // ---------------------------------------------------------------------------------------------------------------
  // Ingresses
  // ---------------------------------------------------------------------------------------------------------------

  static final IngressIdentifier<CommissionServer> COMMISSION_SERVER_INGRESS =
      new IngressIdentifier<>(CommissionServer.class, "io.cloudfun", "commission-server");

  static final IngressIdentifier<DecommissionServer> DECOMMISSION_SERVER_INGRESS =
      new IngressIdentifier<>(DecommissionServer.class, "io.cloudfun", "decommission-server");

  static final IngressIdentifier<ServerMetricReport> SERVER_METRICS_INGRESS =
      new IngressIdentifier<>(ServerMetricReport.class, "io.cloudfun", "server_metrics");

  static final IngressIdentifier<GetUnhealthyRacks> GET_UNHEALTHY_RACKS =
      new IngressIdentifier<>(GetUnhealthyRacks.class, "io.cloudfun", "unhealthy_racks");

  // ---------------------------------------------------------------------------------------------------------------
  // EGRESSES
  // ---------------------------------------------------------------------------------------------------------------

  static final EgressIdentifier<UnhealthyRacks> UNHEALTHY_RACKS_EGRESS =
      new EgressIdentifier<>("io.cloudfun", "unhealthy_racks", UnhealthyRacks.class);

  static final EgressIdentifier<ServerAlert> SERVER_ALERT_EGRESS =
      new EgressIdentifier<>("io.cloudfun", "alerts", ServerAlert.class);

  static final EgressIdentifier<DataCenterAlert> DATA_CENTER_ALERT_EGRESS =
      new EgressIdentifier<>("io.cloudfun", "datacenter-out", DataCenterAlert.class);
}
