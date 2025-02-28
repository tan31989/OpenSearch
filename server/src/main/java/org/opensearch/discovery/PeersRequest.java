/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.discovery;

import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.transport.TransportRequest;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Request sent to a peer node
 *
 * @opensearch.internal
 */
public class PeersRequest extends TransportRequest {
    private final DiscoveryNode sourceNode;
    private final List<DiscoveryNode> knownPeers;

    public PeersRequest(DiscoveryNode sourceNode, List<DiscoveryNode> knownPeers) {
        assert knownPeers.contains(sourceNode) == false : "local node is not a peer";
        this.sourceNode = sourceNode;
        this.knownPeers = knownPeers;
    }

    public PeersRequest(StreamInput in) throws IOException {
        super(in);
        sourceNode = new DiscoveryNode(in);
        knownPeers = in.readList(DiscoveryNode::new);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        sourceNode.writeTo(out);
        out.writeList(knownPeers);
    }

    public List<DiscoveryNode> getKnownPeers() {
        return knownPeers;
    }

    public DiscoveryNode getSourceNode() {
        return sourceNode;
    }

    @Override
    public String toString() {
        return "PeersRequest{" + "sourceNode=" + sourceNode + ", knownPeers=" + knownPeers + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PeersRequest that = (PeersRequest) o;
        return Objects.equals(sourceNode, that.sourceNode) && Objects.equals(knownPeers, that.knownPeers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceNode, knownPeers);
    }
}
