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

import org.apache.flink.statefun.sdk.FunctionType;
import org.apache.flink.statefun.sdk.annotations.Persisted;
import org.apache.flink.statefun.sdk.match.MatchBinder;
import org.apache.flink.statefun.sdk.match.StatefulMatchFunction;
import org.apache.flink.statefun.sdk.state.Expiration;
import org.apache.flink.statefun.sdk.state.PersistedTable;

import io.cloudfun.domain.DataCenterHealthModel;
import io.cloudfun.generated.GetUnhealthyRacks;
import io.cloudfun.generated.Ok;
import io.cloudfun.generated.RackAlert;
import io.cloudfun.generated.UnhealthyRacks;

import java.time.Duration;

public final class DataCenterFun extends StatefulMatchFunction {

  public static final FunctionType Type = new FunctionType("io.cloudfun", "data-center");
  @Persisted
  private final PersistedTable<String, RackAlert> unhealthyRacks =
      PersistedTable.of(
          "racks",
          String.class,
          RackAlert.class,
          Expiration.expireAfterWriting(Duration.ofDays(7)));

  @Override
  public void configure(MatchBinder binder) {
    binder
        .predicate(
            GetUnhealthyRacks.class,
            (context, unused) ->
                context.send(
                    Constants.UNHEALTHY_RACKS_EGRESS,
                    UnhealthyRacks.newBuilder().addAllRackId(unhealthyRacks.keys()).build()))
        .predicate(Ok.class, (context, ok) -> unhealthyRacks.remove(context.caller().id()))
        .predicate(
            RackAlert.class,
            (context, alert) -> {
              unhealthyRacks.set(context.caller().id(), alert);
              DataCenterHealthModel.tryCorrelate(unhealthyRacks.entries())
                  .ifPresent(a -> context.send(Constants.DATA_CENTER_ALERT_EGRESS, a));
            });
  }
}
