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
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.alibaba.nacos.naming.boot.RunningConfig;
import com.alibaba.nacos.naming.core.VirtualClusterDomain;
import com.alibaba.nacos.naming.misc.*;
import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.Response;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.javatuples.Pair;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.GZIPOutputStream;

import static com.alibaba.nacos.common.util.SystemUtils.STANDALONE_MODE;

/**
 * @author nacos
 */
public class RaftCore {

    public static final String API_VOTE = UtilsAndCommons.NACOS_NAMING_CONTEXT + "/raft/vote";

    public static final String API_BEAT = UtilsAndCommons.NACOS_NAMING_CONTEXT + "/raft/beat";

    public static final String API_PUB = UtilsAndCommons.NACOS_NAMING_CONTEXT + "/raft/publish";

    public static final String API_UNSF_PUB = UtilsAndCommons.NACOS_NAMING_CONTEXT + "/raft/unSafePublish";

    public static final String API_DEL = UtilsAndCommons.NACOS_NAMING_CONTEXT + "/raft/delete";

    public static final String API_GET = UtilsAndCommons.NACOS_NAMING_CONTEXT + "/raft/get";

    public static final String API_ON_PUB = UtilsAndCommons.NACOS_NAMING_CONTEXT + "/raft/onPublish";

    public static final String API_ON_DEL = UtilsAndCommons.NACOS_NAMING_CONTEXT + "/raft/onDelete";

    public static final String API_GET_PEER = UtilsAndCommons.NACOS_NAMING_CONTEXT + "/raft/getPeer";

    private static ScheduledExecutorService executor = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);

            t.setDaemon(true);
            t.setName("com.alibaba.nacos.naming.raft.notifier");

            return t;
        }
    });

    public static final Lock OPERATE_LOCK = new ReentrantLock();

    public static final int PUBLISH_TERM_INCREASE_COUNT = 100;

    private static final int INIT_LOCK_TIME_SECONDS = 3;

    private static volatile boolean initialized = false;

    private static Lock lock = new ReentrantLock();

    private static volatile List<RaftListener> listeners = new CopyOnWriteArrayList<>();

    private static ConcurrentMap<String, Datum> datums = new ConcurrentHashMap<String, Datum>();

    private static PeerSet peers = new PeerSet();//集群选举信息列表集合

    private static volatile Notifier notifier = new Notifier();

    public static void init() throws Exception {

        Loggers.RAFT.info("initializing Raft sub-system");

        executor.submit(notifier);//开启订阅通知线程

        peers.add(NamingProxy.getServers());//获取集群所有ip地址封装进PeerSet对象中

        long start = System.currentTimeMillis();

        RaftStore.load();//从缓存文件中读取以往保存的servers信息加载进内存中（private static ConcurrentMap<String, Datum> datums = new ConcurrentHashMap<String, Datum>();）

        Loggers.RAFT.info("cache loaded, peer count: " + peers.size()
                + ", datum count: " + datums.size()
                + ", current term:" + peers.getTerm());

        while (true) {
            if (notifier.tasks.size() <= 0) {
                break;
            }
            Thread.sleep(1000L);
            System.out.println(notifier.tasks.size());
        }

        Loggers.RAFT.info("finish to load data from disk,cost: " + (System.currentTimeMillis() - start) + " ms.");
        
        //开启选举任务线程,500毫秒执行一次
        GlobalExecutor.register(new MasterElection());
        //开启发送心跳线程,向其他集群节点发送心跳(只有leader有权利发送),500毫秒执行一次
        GlobalExecutor.register1(new HeartBeat());
        //服务列表检测线程，检测磁盘中的服务配置和内存中的是否保持一致,5秒执行一次
        GlobalExecutor.register(new AddressServerUpdater(), GlobalExecutor.ADDRESS_SERVER_UPDATE_INTVERAL_MS);

        if (peers.size() > 0) {
            if (lock.tryLock(INIT_LOCK_TIME_SECONDS, TimeUnit.SECONDS)) {
                initialized = true;
                lock.unlock();
            }
        } else {
            throw new Exception("peers is empty.");
        }

        Loggers.RAFT.info("timer started: leader timeout ms: " + GlobalExecutor.LEADER_TIMEOUT_MS
                + "; heart-beat timeout ms: " + GlobalExecutor.HEARTBEAT_INTVERAL_MS);
    }

    public static List<RaftListener> getListeners() {
        return listeners;
    }

    /**
     * will return success once local writes success instead of the majority,
     * therefore is unsafe
     *
     * @param key
     * @param value
     * @throws Exception
     */
    public static void unsafePublish(String key, String value) throws Exception {
        OPERATE_LOCK.lock();

        try {
            if (!RaftCore.isLeader()) {
                JSONObject params = new JSONObject();
                params.put("key", key);
                params.put("value", value);

                Map<String, String> parameters = new HashMap<>(1);
                parameters.put("key", key);
                RaftProxy.proxyPostLarge(API_UNSF_PUB, params.toJSONString(), parameters);

                if (!RaftCore.isLeader()) {
                    throw new IllegalStateException("I'm not leader, can not handle update/delete operation");
                }
            }

            Datum datum = new Datum();
            datum.key = key;
            datum.value = value;
            datum.timestamp.set(RaftCore.getDatum(key).timestamp.incrementAndGet());

            RaftPeer local = peers.local();

            onPublish(datum, local);
        } finally {
            OPERATE_LOCK.unlock();
        }
    }

    public static void signalPublish(String key, String value) throws Exception {

        long start = System.currentTimeMillis();
        final Datum datum = new Datum();
        datum.key = key;
        datum.value = value;

        if (RaftCore.getDatum(key) == null) {
            datum.timestamp.set(1L);
        } else {
            datum.timestamp.set(RaftCore.getDatum(key).timestamp.incrementAndGet());
        }

        JSONObject json = new JSONObject();
        json.put("datum", datum);//需要注册的服务节点信息
        json.put("source", peers.local());//集群选举实例

        onPublish(datum, peers.local());//delete与add的调用同一个方法，这里不同的是如果是将节点删掉，则value置空即可

        final String content = JSON.toJSONString(json);

        for (final String server : peers.allServersIncludeMyself()) {
            if (isLeader(server)) {
                continue;
            }
            final String url = buildURL(server, API_ON_PUB);
            HttpClient.asyncHttpPostLarge(url, Arrays.asList("key=" + key), content, new AsyncCompletionHandler<Integer>() {
                @Override
                public Integer onCompleted(Response response) throws Exception {
                    if (response.getStatusCode() != HttpURLConnection.HTTP_OK) {
                        Loggers.RAFT.warn("RAFT", "failed to publish data to peer, datumId=" + datum.key + ", peer=" + server + ", http code=" + response.getStatusCode());
                        return 1;
                    }
                    return 0;
                }

                @Override
                public STATE onContentWriteCompleted() {
                    return STATE.CONTINUE;
                }
            });

        }

        long end = System.currentTimeMillis();
        Loggers.RAFT.info("signalPublish cost " + (end - start) + " ms" + " : " + key);

    }

    public static void doSignalPublish(String key, String value) throws Exception {
        if (!RaftCore.isLeader()) {//如果当前节点不为leader节点则转发至leader节点进行代理操作
            JSONObject params = new JSONObject();
            params.put("key", key);
            params.put("value", value);
            Map<String, String> parameters = new HashMap<>(1);
            parameters.put("key", key);

            RaftProxy.proxyPostLarge(API_PUB, params.toJSONString(), parameters);

            return;
        }

        //OPERATE_LOCK.lock();//操作锁
        /**
         * 在0.21版本中SignalPublish方法存在这个锁，
         * 那么在外部调用时因为都会默认等5秒，在并发时第一个线程到达这里以后拿到锁
         * 第二个线程则会等待，如果第一个线程执行的时间+第二个线程执行的时间>5000毫秒
         * 则外部的await释放后执行
         * “virtualClusterDomain = (VirtualClusterDomain) domainsManager.getDomain(dom);”
         * 则第二个线程则会为空 从而抛出异常，所以在0.7.0版本中去掉了###操作锁###
         */
        
        if (!RaftCore.isLeader()) {
            throw new IllegalStateException("I'm not leader, can not handle update/delete operation");
        }

        if (key.startsWith(UtilsAndCommons.DOMAINS_DATA_ID)) {
            signalPublishLocked(key, value);
        } else {
            signalPublish(key, value);//发布服务节点
        }
    }

    public static void signalPublishLocked(String key, String value) throws Exception {

        try {
            RaftCore.OPERATE_LOCK.lock();
            long start = System.currentTimeMillis();
            final Datum datum = new Datum();
            datum.key = key;
            datum.value = value;
            if (RaftCore.getDatum(key) == null) {
                datum.timestamp.set(1L);
            } else {
                datum.timestamp.set(RaftCore.getDatum(key).timestamp.incrementAndGet());
            }

            JSONObject json = new JSONObject();
            json.put("datum", datum);
            json.put("source", peers.local());

            onPublish(datum, peers.local());

            final String content = JSON.toJSONString(json);

            final CountDownLatch latch = new CountDownLatch(peers.majorityCount());
            for (final String server : peers.allServersIncludeMyself()) {
                if (isLeader(server)) {
                    latch.countDown();
                    continue;
                }
                final String url = buildURL(server, API_ON_PUB);
                HttpClient.asyncHttpPostLarge(url, Arrays.asList("key=" + key), content, new AsyncCompletionHandler<Integer>() {
                    @Override
                    public Integer onCompleted(Response response) throws Exception {
                        if (response.getStatusCode() != HttpURLConnection.HTTP_OK) {
                            Loggers.RAFT.warn("RAFT", "failed to publish data to peer, datumId=" + datum.key + ", peer=" + server + ", http code=" + response.getStatusCode());
                            return 1;
                        }
                        latch.countDown();
                        return 0;
                    }

                    @Override
                    public STATE onContentWriteCompleted() {
                        return STATE.CONTINUE;
                    }
                });

            }

            if (!latch.await(UtilsAndCommons.RAFT_PUBLISH_TIMEOUT, TimeUnit.MILLISECONDS)) {
                // only majority servers return success can we consider this update success
                Loggers.RAFT.info("data publish failed, caused failed to notify majority, key=" + key);
                throw new IllegalStateException("data publish failed, caused failed to notify majority, key=" + key);
            }

            long end = System.currentTimeMillis();
            Loggers.RAFT.info("signalPublish cost " + (end - start) + " ms" + " : " + key);
        } finally {
            RaftCore.OPERATE_LOCK.unlock();
        }
    }

    public static void signalDelete(final String key) throws Exception {

        OPERATE_LOCK.lock();
        try {

            if (!isLeader()) {
                Map<String, String> params = new HashMap<>(1);
                params.put("key", key);

                RaftProxy.proxyGET(API_DEL, params);
                return;
            }

            if (!RaftCore.isLeader()) {
                throw new IllegalStateException("I'm not leader, can not handle update/delete operation");
            }

            JSONObject json = new JSONObject();
            json.put("key", key);
            json.put("source", peers.local());

            for (final String server : peers.allServersIncludeMyself()) {
                String url = buildURL(server, API_ON_DEL);
                HttpClient.asyncHttpPostLarge(url, null, JSON.toJSONString(json)
                        , new AsyncCompletionHandler<Integer>() {
                            @Override
                            public Integer onCompleted(Response response) throws Exception {
                                if (response.getStatusCode() != HttpURLConnection.HTTP_OK) {
                                    Loggers.RAFT.warn("RAFT", "failed to delete data from peer, datumId=" + key + ", peer=" + server + ", http code=" + response.getStatusCode());
                                    return 1;
                                }

                                RaftPeer local = peers.local();

                                local.resetLeaderDue();

                                return 0;
                            }
                        });
            }
        } finally {
            OPERATE_LOCK.unlock();
        }
    }

    public static void onPublish(JSONObject json) throws Exception {
        Datum datum = JSON.parseObject(json.getString("datum"), Datum.class);
        RaftPeer source = JSON.parseObject(json.getString("source"), RaftPeer.class);
        onPublish(datum, source);
    }

    public static void onPublish(Datum datum, RaftPeer source) throws Exception {
        RaftPeer local = peers.local();
        if (StringUtils.isBlank(datum.value)) {
            Loggers.RAFT.warn("received empty datum");
            throw new IllegalStateException("received empty datum");
        }

        if (!peers.isLeader(source.ip)) {//校验leader合法性
            Loggers.RAFT.warn("peer(" + JSON.toJSONString(source) + ") tried to publish " +
                    "data but wasn't leader, leader: " + JSON.toJSONString(getLeader()));
            throw new IllegalStateException("peer(" + source.ip + ") tried to publish " +
                    "data but wasn't leader");
        }

        if (source.term.get() < local.term.get()) {
            Loggers.RAFT.warn("out of date publish, pub-term: "
                    + JSON.toJSONString(source) + ", cur-term: " + JSON.toJSONString(local));
            throw new IllegalStateException("out of date publish, pub-term:"
                    + source.term.get() + ", cur-term: " + local.term.get());
        }

        local.resetLeaderDue();//重置leader在位周期

        /***
         * key:com.alibaba.nacos.naming.iplist.providers:com.fhqb.mnt.service.lottery.MLotteryServiceApi
         * 
         * 
         *value:
         *
         *[{
			"app": "",
			"clusterName": "DEFAULT",
			"enabled": true,
			"instanceId": "172.16.4.82#20880#DEFAULT#providers:com.fhqb.mnt.service.lottery.MLotteryServiceApi",
			"ip": "172.16.4.82",
			"lastBeat": 1546503561637,
			"marked": false,
			"metadata": {
				"side": "provider",
				"methods": "getVoucherProductTypesByType,getLotteryPrizeUserList,getPrizeProductTypesByType,getLotteryPrizeDetail,getUserPrizeAddress,getVoucherList,getLotteryRule,editLotteryPrize,getLotteryChanceList,exportLotteryPrizeUserList,getPrizeDetile,editLotteryChance,getVoucherTypes,editUserPrizeStatus,getLotteryChanceDetail,editUserPrizeAddress,editPrize,getLotteryPrizeList,getPrizeTypes,gettPrizeList,editLotteryRule",
				"dubbo": "2.0.2",
				"pid": "9420",
				"interface": "com.fhqb.mnt.service.lottery.MLotteryServiceApi",
				"generic": "false",
				"revision": "0.1.4930",
				"protocol": "dubbo",
				"application": "fenghuangqianbao-ht2-provider",
				"category": "providers",
				"anyhost": "true",
				"bean.name": "com.fhqb.mnt.service.lottery.MLotteryServiceApi",
				"timestamp": "1546501166170"
			},
			"port": 20880,
			"serviceName": "providers:com.fhqb.mnt.service.lottery.MLotteryServiceApi",
			"tenant": "",
			"valid": true,
			"weight": 1
		}, {
			"app": "DEFAULT",
			"clusterName": "DEFAULT",
			"enabled": true,
			"instanceId": "172.16.4.82#20882#DEFAULT#providers:com.fhqb.mnt.service.lottery.MLotteryServiceApi",
			"ip": "172.16.4.82",
			"lastBeat": 1546503429369,
			"marked": false,
			"metadata": {},
			"port": 20882,
			"serviceName": "providers:com.fhqb.mnt.service.lottery.MLotteryServiceApi",
			"tenant": "",
			"valid": true,
			"weight": 1
		}]
         * 
         * 
         */
        Datum datumOrigin = RaftCore.getDatum(datum.key);//拿出需要注册的服务节点信息

        if (datumOrigin != null && datumOrigin.timestamp.get() > datum.timestamp.get()) {
            // refuse operation:
            Loggers.RAFT.warn("out of date publish, pub-timestamp:"
                    + datumOrigin.timestamp.get() + ", cur-timestamp: " + datum.timestamp.get());
            return;
        }

        // do apply 如果需要注册的key是com.alibaba.nacos.naming.domains.meta开头的则写到磁盘中持久化
        if (datum.key.startsWith(UtilsAndCommons.DOMAINS_DATA_ID) || UtilsAndCommons.INSTANCE_LIST_PERSISTED) {
            RaftStore.write(datum);//以磁盘文件的方式保存信息 路径为：F:\sam_env\java\nacos\nacos\distribution\target\nacos-server-0.2.1\nacos\data\naming\data\com.alibaba.nacos.naming.iplist.nacos.test.3
        }

        RaftCore.datums.put(datum.key, datum);//同时将新的Datum数据推入内存中

        if (datum.key.startsWith(UtilsAndCommons.DOMAINS_DATA_ID)) {
            if (isLeader()) {//更新local.term,默认注册一次服务term增加100
                local.term.addAndGet(PUBLISH_TERM_INCREASE_COUNT);
            } else {
                if (local.term.get() + PUBLISH_TERM_INCREASE_COUNT > source.term.get()) {
                    //set leader term:
                    getLeader().term.set(source.term.get());
                    local.term.set(getLeader().term.get());
                } else {
                    local.term.addAndGet(PUBLISH_TERM_INCREASE_COUNT);
                }
            }
            RaftStore.updateTerm(local.term.get());
        }

        notifier.addTask(datum, Notifier.ApplyAction.CHANGE);//推送提醒线程

        Loggers.RAFT.info("data added/updated, key=" + datum.key + ", term: " + local.term);
    }

    public static void onDelete(JSONObject params) throws Exception {

        RaftPeer source = new RaftPeer();
        source.ip = params.getJSONObject("source").getString("ip");
        source.state = RaftPeer.State.valueOf(params.getJSONObject("source").getString("state"));
        source.term.set(params.getJSONObject("source").getLongValue("term"));
        source.heartbeatDueMs = params.getJSONObject("source").getLongValue("heartbeatDueMs");
        source.leaderDueMs = params.getJSONObject("source").getLongValue("leaderDueMs");
        source.voteFor = params.getJSONObject("source").getString("voteFor");

        RaftPeer local = peers.local();

        if (!peers.isLeader(source.ip)) {
            Loggers.RAFT.warn("peer(" + JSON.toJSONString(source) + ") tried to publish " +
                    "data but wasn't leader, leader: " + JSON.toJSONString(getLeader()));
            throw new IllegalStateException("peer(" + source.ip + ") tried to publish data but wasn't leader");
        }

        if (source.term.get() < local.term.get()) {
            Loggers.RAFT.warn("out of date publish, pub-term: "
                    + JSON.toJSONString(source) + ", cur-term: " + JSON.toJSONString(local));
            throw new IllegalStateException("out of date publish, pub-term:"
                    + source.term + ", cur-term: " + local.term);
        }

        local.resetLeaderDue();

        // do apply
        String key = params.getString("key");
        deleteDatum(key);

        if (key.startsWith(UtilsAndCommons.DOMAINS_DATA_ID)) {

            if (local.term.get() + PUBLISH_TERM_INCREASE_COUNT > source.term.get()) {
                //set leader term:
                getLeader().term.set(source.term.get());
                local.term.set(getLeader().term.get());
            } else {
                local.term.addAndGet(PUBLISH_TERM_INCREASE_COUNT);
            }

            RaftStore.updateTerm(local.term.get());
        }

    }

    /***
     * 选举说明：
     * 触发选举必要条件：
     * 1.local.leaderDueMs -= GlobalExecutor.TICK_PERIOD_MS必须小于0
     * Q:为什么这么判断？
     * A:代码实现的问题，其中local.leaderDueMs是一个判断标志
     * 选举触发逻辑为：leader每个策略秒会向其他节点发送heartBeat，在发送时会将leader
     * 自身的local.leaderDueMs复位然后发送给其他节点，其他节点当收到leader传来的
     * local.leaderDueMs时会将本地存储的leader节点中的local.leaderDueMs给替换掉，
     * 周而复始。如果leader发生了不可预测的问题时，其他节点会在满足条件1时触发选举。
     * @author dell
     *
     */
    public static class MasterElection implements Runnable {
        @Override
        public void run() {
            try {
                RaftPeer local = peers.local();
                local.leaderDueMs -= GlobalExecutor.TICK_PERIOD_MS;//该时间会发送心跳时刷新该值，所以在standlone运行时不会发生选举
                if (local.leaderDueMs > 0) {//任期大于0则继续当leader
                    return;
                }

                // reset timeout
                local.resetLeaderDue();
                local.resetHeartbeatDue();

                sendVote();//开始选举投票
            } catch (Exception e) {
                Loggers.RAFT.warn("RAFT", "error while master election", e);
            }

        }

        public static void sendVote() {//开始选举投票
            if (!initialized) {
                // not ready yet
                return;
            }

            RaftPeer local = peers.get(NetUtils.localServer());
            Loggers.RAFT.info("leader timeout, start voting,leader: " + JSON.toJSONString(getLeader()) + ", term: " + local.term);

            /***
             * 选举前将各个节点中的leader置空，同时将其他节点的voteFor置空
             */
            peers.reset();//投票之前，将之前的状态重置

            local.term.incrementAndGet();
            local.voteFor = local.ip;
            local.state = RaftPeer.State.CANDIDATE;//将当前应用角色设置成CANDIDATE（候选人）

            Map<String, String> params = new HashMap<String, String>(1);
            params.put("vote", JSON.toJSONString(local));//将选举对象信息发送至其他集群节点
            for (final String server : peers.allServersWithoutMySelf()) {//发送给除了自己以外的所有集群节点
                final String url = buildURL(server, API_VOTE);//https://ip:8848/v1/ns/raft/vote
                try {
                    HttpClient.asyncHttpPost(url, null, params, new AsyncCompletionHandler<Integer>() {
                        @Override
                        public Integer onCompleted(Response response) throws Exception {
                            if (response.getStatusCode() != HttpURLConnection.HTTP_OK) {
                                Loggers.RAFT.error("VIPSRV-RAFT", "vote failed: "
                                        , response.getResponseBody() + " url:" + url);

                                return 1;
                            }

                            RaftPeer peer = JSON.parseObject(response.getResponseBody(), RaftPeer.class);

                            Loggers.RAFT.info("received approve from peer: " + JSON.toJSONString(peer));

                            peers.decideLeader(peer);//本地对对比结果做选举

                            return 0;
                        }
                    });
                } catch (Exception e) {
                    Loggers.RAFT.warn("error while sending vote to server:" + server);
                }
            }
        }

        public static RaftPeer receivedVote(RaftPeer remote) {//https://ip:8848/v1/ns/raft/vote中调用
            if (!peers.contains(remote)) {//过滤配置中没有的远程ip发来的投票
                throw new IllegalStateException("can not find peer: " + remote.ip);
            }

            if (!initialized) {
                throw new IllegalStateException("not ready yet");
            }

            RaftPeer local = peers.get(NetUtils.localServer());//获取当前进程的选举对象
            if (remote.term.get() <= local.term.get()) {//对比，如果远程的term比当前的小，则将本地的RaftPeer对象返回（类似paxos算法）
                String msg = "received illegitimate vote" +
                        ", voter-term:" + remote.term + ", votee-term:" + local.term;

                Loggers.RAFT.info(msg);
                if (StringUtils.isEmpty(local.voteFor)) {
                    local.voteFor = local.ip;
                }

                return local;
            }

            local.resetLeaderDue();
        	/**
        	 * 对比如果远程的term大于当前的term则将本地替换成远程的term，将本地设置为follower并且返回（类似paxos算法）
        	 */
            local.state = RaftPeer.State.FOLLOWER;
            local.voteFor = remote.ip;
            local.term.set(remote.term.get());

            Loggers.RAFT.info("vote " + remote.ip + " as leader, term:" + remote.term);

            return local;
        }
    }

    public static class HeartBeat implements Runnable {
        @Override
        public void run() {
            try {
                RaftPeer local = peers.local();//获取本地RaftPeer对象
                local.heartbeatDueMs -= GlobalExecutor.TICK_PERIOD_MS;
                if (local.heartbeatDueMs > 0) {
                    return;
                }

                local.resetHeartbeatDue();//复位心跳时间

                sendBeat();//向其他集群节点发送心跳(只有leader有权利发送)
            } catch (Exception e) {
                Loggers.RAFT.warn("RAFT", "error while sending beat", e);
            }

        }

        public static void sendBeat() throws IOException, InterruptedException {
            RaftPeer local = peers.local();//获取本地RaftPeer对象
            /***
             * 判断当前节点不为leader并且不是单机模式则直接返回
             */
            if (local.state != RaftPeer.State.LEADER && !STANDALONE_MODE) {
                return;
            }

            Loggers.RAFT.info("[RAFT] send beat with " + datums.size() + " keys.");

            local.resetLeaderDue();//复位leader任期

            // build data
            JSONObject packet = new JSONObject();
            packet.put("peer", local);

            JSONArray array = new JSONArray();

            if (Switch.isSendBeatOnly()) {
                Loggers.RAFT.info("[SEND-BEAT-ONLY] " + String.valueOf(Switch.isSendBeatOnly()));
            }
            
            /***
             * 将缓存在本地注册过的服务都封装进心跳包中
             */
            if (!Switch.isSendBeatOnly()) {
                for (Datum datum : datums.values()) {

                    JSONObject element = new JSONObject();
                    String key;

                    if (datum.key.startsWith(UtilsAndCommons.DOMAINS_DATA_ID)) {
                        key = (datum.key).split(UtilsAndCommons.DOMAINS_DATA_ID)[1];
                        element.put("key", UtilsAndCommons.RAFT_DOM_PRE + key);
                    } else if (datum.key.startsWith(UtilsAndCommons.IPADDRESS_DATA_ID_PRE)) {
                        key = (datum.key).split(UtilsAndCommons.IPADDRESS_DATA_ID_PRE)[1];
                        element.put("key", UtilsAndCommons.RAFT_IPLIST_PRE + key);
                    } else if (datum.key.startsWith(UtilsAndCommons.TAG_DOMAINS_DATA_ID)) {
                        key = (datum.key).split(UtilsAndCommons.TAG_DOMAINS_DATA_ID)[1];
                        element.put("key", UtilsAndCommons.RAFT_TAG_DOM_PRE + key);
                    } else if (datum.key.startsWith(UtilsAndCommons.NODE_TAG_IP_PRE)) {
                        key = (datum.key).split(UtilsAndCommons.NODE_TAG_IP_PRE)[1];
                        element.put("key", UtilsAndCommons.RAFT_TAG_IPLIST_PRE + key);
                    }
                    element.put("timestamp", datum.timestamp);

                    array.add(element);
                }
            } else {
                Loggers.RAFT.info("[RAFT] send beat only.");
            }

            packet.put("datums", array);
            // broadcast
            Map<String, String> params = new HashMap<String, String>(1);
            params.put("beat", JSON.toJSONString(packet));
            /**
             * 心跳包内容主要包含本地已注册的服务信息列表以及本地的选举对象,
             * 心跳包{"peer":"{local}","datums":"{[datum],[datum],[datum]}"}
             */
            String content = JSON.toJSONString(params);
            /**
             * 通过gzip压缩的方式传输数据
             */
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            GZIPOutputStream gzip = new GZIPOutputStream(out);
            gzip.write(content.getBytes("UTF-8"));
            gzip.close();

            byte[] compressedBytes = out.toByteArray();
            String compressedContent = new String(compressedBytes, "UTF-8");
            Loggers.RAFT.info("raw beat data size: " + content.length() + ", size of compressed data: " + compressedContent.length());

            for (final String server : peers.allServersWithoutMySelf()) {//遍历发送所有的集群节点（不包含当前节点）
                try {
                    final String url = buildURL(server, API_BEAT);//https://ip:8848/v1/ns/raft/beat
                    Loggers.RAFT.info("send beat to server " + server);
                    HttpClient.asyncHttpPostLarge(url, null, compressedBytes, new AsyncCompletionHandler<Integer>() {
                        @Override
                        public Integer onCompleted(Response response) throws Exception {
                            if (response.getStatusCode() != HttpURLConnection.HTTP_OK) {
                                Loggers.RAFT.error("VIPSRV-RAFT", "beat failed: " + response.getResponseBody() + ", peer: " + server);
                                return 1;
                            }

                            peers.update(JSON.parseObject(response.getResponseBody(), RaftPeer.class));
                            Loggers.RAFT.info("receive beat response from: " + url);
                            return 0;
                        }

                        @Override
                        public void onThrowable(Throwable t) {
                            Loggers.RAFT.error("VIPSRV-RAFT", "error while sending heart-beat to peer: " + server, t);
                        }
                    });
                } catch (Exception e) {
                    Loggers.RAFT.error("VIPSRV-RAFT", "error while sending heart-beat to peer: " + server, e);
                }
            }

        }

        /***
         * 心跳接收方接收心跳包以后会有以下处理：
         * 
         * 1、重新判断发来心跳包来的leader是否为当前节点所缓存的leader,
         * 如果不相等则重新从新主的节点中获取最新leader信息并更新至本地缓存中
         * 
         * 2、更新服务缓存列表(磁盘更新&缓存更新)
         * 
         * 3、将leader中已经不存在但是在当前节点中存在的服务列表信息从本地移除（磁盘清理&缓存清理）
         * 
         * @param beat
         * @return
         * @throws Exception
         */
        public static RaftPeer receivedBeat(JSONObject beat) throws Exception {
            final RaftPeer local = peers.local();//心跳接收方选举对象
            final RaftPeer remote = new RaftPeer();
            remote.ip = beat.getJSONObject("peer").getString("ip");//发送方选举ip
            remote.state = RaftPeer.State.valueOf(beat.getJSONObject("peer").getString("state"));//发送方状态(LEADER?FOLLOWER?CANDIDATE?)
            remote.term.set(beat.getJSONObject("peer").getLongValue("term"));//发送方选举票数
            remote.heartbeatDueMs = beat.getJSONObject("peer").getLongValue("heartbeatDueMs");
            remote.leaderDueMs = beat.getJSONObject("peer").getLongValue("leaderDueMs");
            remote.voteFor = beat.getJSONObject("peer").getString("voteFor");//发送方的选举投票方
            
            /**
             * 拦截角色不为leader发来的packet包
             */
            if (remote.state != RaftPeer.State.LEADER) {
                Loggers.RAFT.info("[RAFT] invalid state from master, state=" + remote.state + ", remote peer: " + JSON.toJSONString(remote));
                throw new IllegalArgumentException("invalid state from master, state=" + remote.state);
            }
            
            /**
             * 如果有本地票数大于leader票数的情况则直接抛出异常
             */
            if (local.term.get() > remote.term.get()) {
                Loggers.RAFT.info("[RAFT] out of date beat, beat-from-term: " + remote.term.get()
                        + ", beat-to-term: " + local.term.get() + ", remote peer: " + JSON.toJSONString(remote) + ", and leaderDueMs: " + local.leaderDueMs);
                throw new IllegalArgumentException("out of date beat, beat-from-term: " + remote.term.get()
                        + ", beat-to-term: " + local.term.get());
            }
            
            /**
             * 如果当前节点的状态不为FOLLOWER的话则强制更新为FOLLOWER，
             * 并将自己的的选举投票方设置为LEADER的ip
             */
            if (local.state != RaftPeer.State.FOLLOWER) {

                Loggers.RAFT.info("[RAFT] make remote as leader " + ", remote peer: " + JSON.toJSONString(remote));
                // mk follower
                local.state = RaftPeer.State.FOLLOWER;
                local.voteFor = remote.ip;
            }

            final JSONArray beatDatums = beat.getJSONArray("datums");//发送方服务列表
            local.resetLeaderDue();
            local.resetHeartbeatDue();

            /***
             * 重新判断发来心跳包来的leader是否为当前节点所缓存的leader,
             * 如果不相等则重新从新主的节点中获取最新leader信息并更新至本地缓存中
             */
            peers.makeLeader(remote);

            Map<String, Integer> receivedKeysMap = new HashMap<String, Integer>(RaftCore.datums.size());

            for (Map.Entry<String, Datum> entry : RaftCore.datums.entrySet()) {
                receivedKeysMap.put(entry.getKey(), 0);//当前节点服务名map集合
            }

            // now check datums
            List<String> batch = new ArrayList<String>();
            if (!Switch.isSendBeatOnly()) {
                int processedCount = 0;
                Loggers.RAFT.info("[RAFT] received beat with " + beatDatums.size() + " keys, RaftCore.datums' size is "
                        + RaftCore.datums.size() + ",  remote server: " + remote.ip + ", term: " + remote.term + ",  local term: " + local.term);
                /**
                 * 遍历发送方服务列表
                 */
                for (Object object : beatDatums) {
                    processedCount = processedCount + 1;

                    JSONObject entry = (JSONObject) object;
                    String key = entry.getString("key");
                    final String datumKey;

                    if (key.startsWith(UtilsAndCommons.RAFT_DOM_PRE)) {
                        int index = key.indexOf(UtilsAndCommons.RAFT_DOM_PRE);
                        datumKey = UtilsAndCommons.DOMAINS_DATA_ID + key.substring(index + UtilsAndCommons.RAFT_DOM_PRE.length());
                    } else if (key.startsWith(UtilsAndCommons.RAFT_IPLIST_PRE)) {
                        int index = key.indexOf(UtilsAndCommons.RAFT_IPLIST_PRE);
                        datumKey = UtilsAndCommons.IPADDRESS_DATA_ID_PRE + key.substring(index + UtilsAndCommons.RAFT_IPLIST_PRE.length());
                    } else if (key.startsWith(UtilsAndCommons.RAFT_TAG_DOM_PRE)) {
                        int index = key.indexOf(UtilsAndCommons.RAFT_TAG_DOM_PRE);
                        datumKey = UtilsAndCommons.TAG_DOMAINS_DATA_ID + key.substring(index + UtilsAndCommons.RAFT_TAG_DOM_PRE.length());
                    } else {
                        int index = key.indexOf(UtilsAndCommons.RAFT_TAG_IPLIST_PRE);
                        datumKey = UtilsAndCommons.NODE_TAG_IP_PRE + key.substring(index + UtilsAndCommons.RAFT_TAG_IPLIST_PRE.length());
                    }

                    long timestamp = entry.getLong("timestamp");

                    receivedKeysMap.put(datumKey, 1);

                    try {
                        if (RaftCore.datums.containsKey(datumKey) && RaftCore.datums.get(datumKey).timestamp.get() >= timestamp && processedCount < beatDatums.size()) {
                            continue;
                        }
                        //当本地的服务注册列表没有leader节点中的服务列表时则加入进batch集合中
                        if (!(RaftCore.datums.containsKey(datumKey) && RaftCore.datums.get(datumKey).timestamp.get() >= timestamp)) {
                            batch.add(datumKey);
                        }

                        if (batch.size() < 50 && processedCount < beatDatums.size()) {
                            continue;
                        }

                        String keys = StringUtils.join(batch, ",");//将list中的数据以逗号分割后拼接成字符串

                        if (batch.size() <= 0) {
                            continue;
                        }

                        Loggers.RAFT.info("get datums from leader: " + getLeader().ip + " , batch size is " + batch.size() + ", processedCount is " + processedCount
                                + ", datums' size is " + beatDatums.size() + ", RaftCore.datums' size is " + RaftCore.datums.size());

                        // update datum entry
                        /**
                         * 服务列表获取接口：https:ip:8848/v1/ns/raft/get?keys=
                         * 获取leader所有的接口服务列表后和本地对比，然后做新增和删除操作
                         */
                        String url = buildURL(remote.ip, API_GET) + "?keys=" + keys;
                        HttpClient.asyncHttpGet(url, null, null, new AsyncCompletionHandler<Integer>() {
                            @Override
                            public Integer onCompleted(Response response) throws Exception {
                                if (response.getStatusCode() != HttpURLConnection.HTTP_OK) {
                                    return 1;
                                }
                            	/**
                            	 * 获取leader节点最新的服务注册列表信息
                            	 */
                                List<Datum> datumList = JSON.parseObject(response.getResponseBody(), new TypeReference<List<Datum>>() {
                                });

                                for (Datum datum : datumList) {
                                    OPERATE_LOCK.lock();
                                    try {
                                    	//获取老的datum
                                        Datum oldDatum = RaftCore.getDatum(datum.key);

                                        if (oldDatum != null && datum.timestamp.get() <= oldDatum.timestamp.get()) {
                                            Loggers.RAFT.info("[VIPSRV-RAFT] timestamp is smaller than that of mine, key: " + datum.key
                                                    + ",remote: " + datum.timestamp + ", local: " + oldDatum.timestamp);
                                            continue;
                                        }
                                        
                                        /**
                                         * 将从leader节点获取的服务注册信息写入当前节点的磁盘中
                                         */
                                        if (datum.key.startsWith(UtilsAndCommons.DOMAINS_DATA_ID) ||
                                                UtilsAndCommons.INSTANCE_LIST_PERSISTED) {
                                            RaftStore.write(datum);
                                        }
                                        //将从leader节点获取的服务注册信息写入当前节点的缓存中
                                        RaftCore.datums.put(datum.key, datum);
                                        local.resetLeaderDue();//复位leader任期

                                        /**
                                         * 修改事务（term）值
                                         */
                                        if (datum.key.startsWith(UtilsAndCommons.DOMAINS_DATA_ID)) {
                                            if (local.term.get() + 100 > remote.term.get()) {
                                                getLeader().term.set(remote.term.get());
                                                local.term.set(getLeader().term.get());
                                            } else {
                                                local.term.addAndGet(100);
                                            }

                                            RaftStore.updateTerm(local.term.get());
                                        }

                                        Loggers.RAFT.info("data updated" + ", key=" + datum.key
                                                + ", timestamp=" + datum.timestamp + ",from " + JSON.toJSONString(remote) + ", local term: " + local.term);
                                        
                                        /***
                                         * 从leader中同步到新的datum则通过udp的方式推送给订阅了当前Nacos节点的客户端
                                         */
                                        notifier.addTask(datum, Notifier.ApplyAction.CHANGE);
                                    } catch (Throwable e) {
                                        Loggers.RAFT.error("RAFT-BEAT", "failed to sync datum from leader, key: " + datum.key, e);
                                    } finally {
                                        OPERATE_LOCK.unlock();
                                    }
                                }
                                TimeUnit.MILLISECONDS.sleep(200);
                                return 0;
                            }
                        });

                        batch.clear();//每一次或者每一组结束后将batch集合重置

                    } catch (Exception e) {
                        Loggers.RAFT.error("VIPSRV-RAFT", "failed to handle beat entry, key=" + datumKey);
                    }

                }

                /***
                 * 将leader没有但是存在于当前节点的服务删除（删除磁盘文件以及缓存）
                 */
                List<String> deadKeys = new ArrayList<String>();
                for (Map.Entry<String, Integer> entry : receivedKeysMap.entrySet()) {
                    if (entry.getValue() == 0) {
                        deadKeys.add(entry.getKey());
                    }
                }

                for (String deadKey : deadKeys) {
                    try {
                        deleteDatum(deadKey);
                    } catch (Exception e) {
                        Loggers.RAFT.error("VIPSRV-RAFT", "failed to remove entry, key=" + deadKey, e);
                    }
                }

            }


            return local;
        }
    }

    /***
     * 核心功能：
     * 检测磁盘中的服务配置和内存中的是否保持一致
     * 
     * 描述：
     * 该接口主要做磁盘扫描和缓存中的服务列表进行对比
     * 如果新盘有新的服务列表添加，则缓存中删除，将新的服务添加进缓存中
     * @author dell
     *
     */
    public static class AddressServerUpdater implements Runnable {
        @Override
        public void run() {
            try {
                List<String> servers = NamingProxy.getServers();//获取cluster.conf配置文件中配置的集群节点ip列表
                List<RaftPeer> peerList = new ArrayList<RaftPeer>(peers.allPeers());//当前节点缓存的集群选举信息列表
                List<String> oldServers = new ArrayList<String>();

                if (CollectionUtils.isEmpty(servers)) {
                    Loggers.RAFT.warn("get empty server list from address server,ignore it.");
                    return;
                }

                for (RaftPeer peer : peerList) {
                    oldServers.add(peer.ip);//老的集群节点ip列表
                }
                //新老对比,取出缓存中没有在磁盘中存在的服务列表,然后重新加入到缓存中
                List<String> newServers = (List<String>) CollectionUtils.subtract(servers, oldServers);
                if (!CollectionUtils.isEmpty(newServers)) {
                    peers.add(newServers);
                    Loggers.RAFT.info("[RAFT] server list is updated, new (" + newServers.size() + ") servers: " + newServers);
                }
                //获取在配置文件中不存在,但是在缓存中存在的服务列表,然后删除
                List<String> deadServers = (List<String>) CollectionUtils.subtract(oldServers, servers);
                if (!CollectionUtils.isEmpty(deadServers)) {
                    peers.remove(deadServers);//删除
                    Loggers.RAFT.info("[RAFT] server list is updated, dead (" + deadServers.size() + ") servers: " + deadServers);
                }
            } catch (Exception e) {
                Loggers.RAFT.info("[RAFT] error while updating server list.", e);
            }
        }
    }

    public static void listen(RaftListener listener) {
        if (listeners.contains(listener)) {
            return;
        }

        listeners.add(listener);

        for (RaftListener listener1 : listeners) {
            if (listener1 instanceof VirtualClusterDomain) {
                Loggers.RAFT.debug("listener in listeners: " + ((VirtualClusterDomain) listener1).getName());
            }
        }

        if (listeners.contains(listener)) {
            if (listener instanceof VirtualClusterDomain) {
                Loggers.RAFT.info("add listener: " + ((VirtualClusterDomain) listener).getName());
            } else {
                Loggers.RAFT.info("add listener for switch or domain meta. ");
            }
        } else {
            Loggers.RAFT.error("VIPSRV-RAFT", "faild to add listener: " + JSON.toJSONString(listener));
        }
        // if data present, notify immediately
        for (Datum datum : datums.values()) {
            if (!listener.interests(datum.key)) {
                continue;
            }

            try {
                listener.onChange(datum.key, datum.value);
            } catch (Exception e) {
                Loggers.RAFT.error("VIPSRV-RAFT", "failed to notify listener", e);
            }
        }
    }

    public static void unlisten(String key) {
        for (RaftListener listener : listeners) {
            if (listener.matchUnlistenKey(key)) {
                listeners.remove(listener);
            }
        }
    }

    public static void setTerm(long term) {
        RaftCore.peers.setTerm(term);
    }

    public static long getTerm() {
        return RaftCore.peers.getTerm();
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static boolean isLeader(String ip) {
        return peers.isLeader(ip);
    }

    public static boolean isLeader() {
        return peers.isLeader(NetUtils.localServer());
    }

    public static String buildURL(String ip, String api) {
        if (!ip.contains(UtilsAndCommons.CLUSTER_CONF_IP_SPLITER)) {
            ip = ip + UtilsAndCommons.CLUSTER_CONF_IP_SPLITER + RunningConfig.getServerPort();
        }
        return "http://" + ip + RunningConfig.getContextPath() + api;
    }

    public static Datum getDatum(String key) {
        return datums.get(key);
    }

    public static RaftPeer getLeader() {
        return peers.getLeader();
    }

    public static List<RaftPeer> getPeers() {//peers节点存储方式为hashmap，则表示服务启动顺序没有任何优势
        return new ArrayList<RaftPeer>(peers.allPeers());
    }

    public static PeerSet getPeerSet() {
        return peers;
    }

    public static void setPeerSet(PeerSet peerSet) {
        peers = peerSet;
    }

    public static int datumSize() {
        return datums.size();
    }

    public static void addDatum(Datum datum) {
        datums.put(datum.key, datum);
        notifier.addTask(datum, Notifier.ApplyAction.CHANGE);
    }

    private static void deleteDatum(String key) {
        Datum deleted = datums.remove(key);
        if (deleted != null) {
            if (key.startsWith(UtilsAndCommons.DOMAINS_DATA_ID)) {
                RaftStore.delete(deleted);
            }
            notifier.addTask(deleted, Notifier.ApplyAction.DELETE);
            Loggers.RAFT.info("datum deleted, key=" + key);
        }
    }

    public static class Notifier implements Runnable {

        private ConcurrentHashMap<String, String> services = new ConcurrentHashMap<>(10 * 1024);

        private BlockingQueue<Pair> tasks = new LinkedBlockingQueue<Pair>(1024 * 1024);

        public void addTask(Datum datum, ApplyAction action) {

            if (services.containsKey(datum.key) && action == ApplyAction.CHANGE) {
                return;
            }
            if (action == ApplyAction.CHANGE) {
                services.put(datum.key, StringUtils.EMPTY);
            }
            tasks.add(Pair.with(datum, action));
        }

        @Override
        public void run() {
            Loggers.RAFT.info("raft notifier started");
            
            /**
             * 订阅者推送线程，如果在注册中心修改了节点，
             * 则会将修改之前和修改之后的数据封装成pair对象发送给订阅者
             */
            while (true) {
                try {

                    Pair pair = tasks.take();

                    if (pair == null) {
                        continue;
                    }

                    Datum datum = (Datum) pair.getValue0();
                    ApplyAction action = (ApplyAction) pair.getValue1();

                    services.remove(datum.key);

                    int count = 0;
                    //[com.alibaba.nacos.naming.misc.Switch$1@360f88c9, 
                    //com.alibaba.nacos.naming.core.DomainsManager$4@4babf4d9, 
                    //com.alibaba.nacos.naming.core.VirtualClusterDomain@3b60f7ce]
                    for (RaftListener listener : listeners) {

                        if (listener instanceof VirtualClusterDomain) {
                            Loggers.RAFT.debug("listener: " + ((VirtualClusterDomain) listener).getName());
                        }

                        if (!listener.interests(datum.key)) {
                            continue;
                        }

                        count++;

                        try {
                            if (action == ApplyAction.CHANGE) {//新增、修改、删除时走该判断
                                listener.onChange(datum.key, getDatum(datum.key).value);//VirtualClusterDomain.onChange
                                continue;
                            }

                            if (action == ApplyAction.DELETE) {
                                listener.onDelete(datum.key, datum.value);
                                continue;
                            }
                        } catch (Throwable e) {
                            Loggers.RAFT.error("VIPSRV-RAFT", "error while notifying listener of key: "
                                    + datum.key, e);
                        }
                    }

                    Loggers.RAFT.debug("VIPSRV-RAFT", "datum change notified" +
                            ", key: " + datum.key + "; listener count: " + count);
                } catch (Throwable e) {
                    Loggers.RAFT.error("VIPSRV-RAFT", "Error while handling notifying task", e);
                }
            }
        }

        public enum ApplyAction {
            /**
             * Data changed
             */
            CHANGE,
            /**
             * Data deleted
             */
            DELETE
}
    }
}
