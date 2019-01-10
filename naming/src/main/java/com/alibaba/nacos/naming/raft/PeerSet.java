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

import com.alibaba.fastjson.JSON;
import com.alibaba.nacos.naming.misc.HttpClient;
import com.alibaba.nacos.naming.misc.Loggers;
import com.alibaba.nacos.naming.misc.NetUtils;
import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.Response;
import org.apache.commons.collections.SortedBag;
import org.apache.commons.collections.bag.TreeBag;
import org.apache.commons.lang3.StringUtils;

import java.net.HttpURLConnection;
import java.util.*;

import static com.alibaba.nacos.common.util.SystemUtils.STANDALONE_MODE;

/**
 * @author nacos
 */
public class PeerSet {

    private RaftPeer leader = null;

    private static Map<String, RaftPeer> peers = new HashMap<String, RaftPeer>();

    private static Set<String> sites = new HashSet<>();

    public PeerSet() {
    }

    public RaftPeer getLeader() {
        if (STANDALONE_MODE) {//判断是否是单机启动，如果是单机启动则直接返回本地peer
            return local();
        }
        return leader;
    }

    public Set<String> allSites() {
        return sites;
    }

    public void add(List<String> servers) {
        for (String server : servers) {
            RaftPeer peer = new RaftPeer();
            peer.ip = server;

            peers.put(server, peer);
        }

        if (STANDALONE_MODE) {
            RaftPeer local = local();
            local.state = RaftPeer.State.LEADER;
            local.voteFor = NetUtils.localServer();

        }
    }

    public void remove(List<String> servers) {
        for (String server : servers) {
            peers.remove(server);
        }
    }

    public RaftPeer update(RaftPeer peer) {
        peers.put(peer.ip, peer);
        return peer;
    }

    public boolean isLeader(String ip) {
        if (STANDALONE_MODE) {//单机模式默认为leader
            return true;
        }

        if (leader == null) {
            Loggers.RAFT.warn("[IS LEADER] no leader is available now!");
            return false;
        }

        return StringUtils.equals(leader.ip, ip);//判断本地ip是否为leaderip true则表示为leader
    }

    public Set<String> allServersIncludeMyself() {
        return peers.keySet();
    }

    public Set<String> allServersWithoutMySelf() {
        Set<String> servers = new HashSet<String>(peers.keySet());

        // exclude myself
        servers.remove(local().ip);

        return servers;
    }

    public Collection<RaftPeer> allPeers() {
        return peers.values();
    }

    public int size() {
        return peers.size();
    }

    public RaftPeer decideLeader(RaftPeer candidate) {
        peers.put(candidate.ip, candidate);//更新投票请求发送后返回的raftpeer

        SortedBag ips = new TreeBag();
        int maxApproveCount = 0;
        String maxApprovePeer = null;
        for (RaftPeer peer : peers.values()) {
            if (StringUtils.isEmpty(peer.voteFor)) {
                continue;
            }
            /***
             * 数据结构：[2:192.168.0.1,1:192.168.0.3]
             * 解释：
             * 2：为重复的数量，在这里表示192.168.1.1有两个重复的，
             * 可以解释为192.168.1.1这个ip的节点获得了两票
             * 1：ip为192.168.0.3获得了1票
             * 
             * peers里有三个元素为什么最后ips只有2个?因为在发送投票请求时,
             * 接收方的term比发起方的term小所以接收方将自己的voteFor改为了发起方的voteFor,
             * 所以这里过滤掉重复的voteFor，同时将得票数+1
             */
            ips.add(peer.voteFor);
            /***
             * 拿ips最后一个元素的得票数与（集群节点 / 2） + 1 个数量比较（暂为α）
             * 如果票数大于等于α则ips最后一个元素选为leader
             * 如果下标小于α则返回当前的leader
             */
            if (ips.getCount(peer.voteFor) > maxApproveCount) {
                maxApproveCount = ips.getCount(peer.voteFor);
                maxApprovePeer = peer.voteFor;
            }
        }

        if (maxApproveCount >= majorityCount()) {
            RaftPeer peer = peers.get(maxApprovePeer);
            peer.state = RaftPeer.State.LEADER;

            if (!Objects.equals(leader, peer)) {
                leader = peer;
                Loggers.RAFT.info(leader.ip + " has become the LEADER");
            }
        }

        return leader;
    }

    /***
     * 重新判断发来心跳包来的leader是否为当前节点所缓存的leader,
     * 如果不相等则重新从新主的节点中获取最新leader信息并更新至本地缓存中
     * @param candidate
     * @return
     */
    public RaftPeer makeLeader(RaftPeer candidate) {
        if (!Objects.equals(leader, candidate)) {
            leader = candidate;
            Loggers.RAFT.info(leader.ip + " has become the LEADER" + ",local :" + JSON.toJSONString(local()) + ", leader: " + JSON.toJSONString(leader));
        }

        for (final RaftPeer peer : peers.values()) {
            Map<String, String> params = new HashMap<String, String>(1);
            /***
             * 远程节点必须是leader，并且和当前节点缓存中的leader不是一个对象则执行
             */
            if (!Objects.equals(peer, candidate) && peer.state == RaftPeer.State.LEADER) {
                try {
                    String url = RaftCore.buildURL(peer.ip, RaftCore.API_GET_PEER);//https://ip:8848/v1/ns/raft/getPeer
                    HttpClient.asyncHttpPost(url, null, params, new AsyncCompletionHandler<Integer>() {
                        @Override
                        public Integer onCompleted(Response response) throws Exception {
                            if (response.getStatusCode() != HttpURLConnection.HTTP_OK) {
                                Loggers.RAFT.error("VIPSRV-RAFT", "get peer failed: " + response.getResponseBody() + ", peer: " + peer.ip);
                                peer.state = RaftPeer.State.FOLLOWER;
                                return 1;
                            }

                            update(JSON.parseObject(response.getResponseBody(), RaftPeer.class));//重新修改当前节点leader节点缓存信息

                            return 0;
                        }
                    });
                } catch (Exception e) {
                    peer.state = RaftPeer.State.FOLLOWER;
                    Loggers.RAFT.error("VIPSRV-RAFT", "error while getting peer from peer: " + peer.ip);
                }
            }
        }

        return update(candidate);
    }

    public RaftPeer local() {
        RaftPeer peer = peers.get(NetUtils.localServer());
        if (peer == null) {
            throw new IllegalStateException("unable to find local peer: " + NetUtils.localServer() + ", all peers: "
                    + Arrays.toString(peers.keySet().toArray()));
        }

        return peer;
    }

    public RaftPeer get(String server) {
        return peers.get(server);
    }

    public int majorityCount() {
        return peers.size() / 2 + 1;
    }

    public void reset() {

        leader = null;

        for (RaftPeer peer : peers.values()) {
            peer.voteFor = null;
        }
    }

    public void setTerm(long term) {
        RaftPeer local = local();

        if (term < local.term.get()) {
            return;
        }

        local.term.set(term);
    }

    public long getTerm() {
        return local().term.get();
    }

    public boolean contains(RaftPeer remote) {
        return peers.containsKey(remote.ip);
    }
}
