package com.inmobi.databus.partition;

/*
 * #%L
 * messaging-client-databus
 * %%
 * Copyright (C) 2012 - 2014 InMobi
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import com.inmobi.databus.files.StreamFile;
import com.inmobi.messaging.consumer.databus.MessageCheckpoint;

import java.util.HashMap;
import java.util.Map;

public class DeltaPartitionCheckPoint implements MessageCheckpoint {
  private final Map<Integer, PartitionCheckpoint> deltaCheckpoint =
      new HashMap<Integer, PartitionCheckpoint>();

  public DeltaPartitionCheckPoint(StreamFile streamFile, long lineNum,
      Integer minId, Map<Integer, PartitionCheckpoint> deltaCheckpoint) {
    this.deltaCheckpoint.putAll(deltaCheckpoint);
    this.deltaCheckpoint.put(minId,
        new PartitionCheckpoint(streamFile, lineNum));
  }

  public DeltaPartitionCheckPoint(
      Map<Integer, PartitionCheckpoint> deltaCheckpoint) {
    this.deltaCheckpoint.putAll(deltaCheckpoint);
  }

  @Override
  public String toString() {
    return this.deltaCheckpoint.toString();
  }

  public Map<Integer, PartitionCheckpoint> getDeltaCheckpoint() {
    return deltaCheckpoint;
  }

  @Override
  public boolean isNULL() {
    return false;
  }
}
