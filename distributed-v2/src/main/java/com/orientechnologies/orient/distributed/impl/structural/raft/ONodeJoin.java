package com.orientechnologies.orient.distributed.impl.structural.raft;

import com.orientechnologies.orient.core.db.config.ONodeIdentity;
import com.orientechnologies.orient.distributed.OrientDBDistributed;
import com.orientechnologies.orient.distributed.impl.structural.OStructuralNodeConfiguration;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import static com.orientechnologies.orient.distributed.impl.coordinator.OCoordinateMessagesFactory.NODE_JOIN_REQUEST;

public class ONodeJoin implements ORaftOperation {
  private ONodeIdentity nodeIdentity;

  public ONodeJoin(ONodeIdentity identity) {
    this.nodeIdentity = identity;
  }

  public ONodeJoin() {

  }

  @Override
  public void apply(OrientDBDistributed context) {
    context.getStructuralConfiguration().getSharedConfiguration().addNode(new OStructuralNodeConfiguration(nodeIdentity));
  }

  @Override
  public void serialize(DataOutput output) throws IOException {
    nodeIdentity.serialize(output);
  }

  @Override
  public int getRequestType() {
    return NODE_JOIN_REQUEST;
  }

  @Override
  public void deserialize(DataInput input) throws IOException {
    this.nodeIdentity = new ONodeIdentity();
    this.nodeIdentity.deserialize(input);
  }
}
