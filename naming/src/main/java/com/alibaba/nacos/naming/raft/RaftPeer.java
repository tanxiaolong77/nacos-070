/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
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
 */
package com.alibaba.nacos.naming.raft;

import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author nacos
 */
public class RaftPeer {

    public String ip;

    public String voteFor;//投票ip
    /***
     * 在F:\sam_env\java\nacos\nacos\distribution\target\nacos-server-0.2.1
     * \nacos\data\naming\meta.properties中维护,change操作加100
     * 可以将该字段看成zk的zxid（事务版本号），当主的进行一次change，
     * delete操作时会修改该字段，然后通过心跳的方式发送给集群的其他节点
     */
    public AtomicLong term = new AtomicLong(0L);

    public volatile long leaderDueMs = RandomUtils.nextLong(0, GlobalExecutor.LEADER_TIMEOUT_MS);

    public volatile  long heartbeatDueMs = RandomUtils.nextLong(0, GlobalExecutor.HEARTBEAT_INTVERAL_MS);

    public State state = State.FOLLOWER;

    public void resetLeaderDue() {//15000+(0 ~ 5000) ms
        leaderDueMs = GlobalExecutor.LEADER_TIMEOUT_MS + RandomUtils.nextLong(0, GlobalExecutor.RAMDOM_MS);
    }

    public void resetHeartbeatDue() {
        heartbeatDueMs = GlobalExecutor.HEARTBEAT_INTVERAL_MS;
    }

    public enum State {
        /**
         * Leader of the cluster, only one leader stands in a cluster
         */
        LEADER,
        /**
         * Follower of the cluster, report to and copy from leader
         */
        FOLLOWER,
        /**
         * Candidate leader to be elected
         */
        CANDIDATE
    }

    @Override
    public int hashCode() {
        return Objects.hash(ip);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }

        if (!(obj instanceof RaftPeer)) {
            return false;
        }

        RaftPeer other = (RaftPeer) obj;

        return StringUtils.equals(ip, other.ip);
    }
}
