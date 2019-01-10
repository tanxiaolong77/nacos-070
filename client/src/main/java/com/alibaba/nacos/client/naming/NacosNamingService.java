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
package com.alibaba.nacos.client.naming;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.common.Constants;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.pojo.Cluster;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.api.naming.pojo.ListView;
import com.alibaba.nacos.api.naming.pojo.ServiceInfo;
import com.alibaba.nacos.api.selector.AbstractSelector;
import com.alibaba.nacos.client.naming.beat.BeatInfo;
import com.alibaba.nacos.client.naming.beat.BeatReactor;
import com.alibaba.nacos.client.naming.core.Balancer;
import com.alibaba.nacos.client.naming.core.EventDispatcher;
import com.alibaba.nacos.client.naming.core.HostReactor;
import com.alibaba.nacos.client.naming.net.NamingProxy;
import com.alibaba.nacos.client.naming.utils.CollectionUtils;
import com.alibaba.nacos.client.naming.utils.LogUtils;
import com.alibaba.nacos.client.naming.utils.StringUtils;
import com.alibaba.nacos.client.naming.utils.UtilAndComs;

import org.apache.commons.lang3.BooleanUtils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

/**
 * @author <a href="mailto:zpf.073@gmail.com">nkorange</a>
 */
@SuppressWarnings("PMD.ServiceOrDaoClassShouldEndWithImplRule")
public class NacosNamingService implements NamingService {//在NamingFactory.createNamingService时使用

    /**
     * Each Naming instance should have different namespace.
     */
    private String namespace;

    private String endpoint;

    private String serverList;

    private String cacheDir;

    private String logName;

    private HostReactor hostReactor;

    private BeatReactor beatReactor;

    private EventDispatcher eventDispatcher;

    private NamingProxy serverProxy;

    private void init() {

        namespace = System.getProperty(PropertyKeyConst.NAMESPACE);

        if (StringUtils.isEmpty(namespace)) {
            namespace = UtilAndComs.DEFAULT_NAMESPACE_ID;
        }

        logName = System.getProperty(UtilAndComs.NACOS_NAMING_LOG_NAME);
        if (StringUtils.isEmpty(logName)) {
            logName = "naming.log";
        }

        String logLevel = System.getProperty(UtilAndComs.NACOS_NAMING_LOG_LEVEL);
        if (StringUtils.isEmpty(logLevel)) {
            logLevel = "INFO";
        }

        LogUtils.setLogLevel(logLevel);

        cacheDir = System.getProperty("com.alibaba.nacos.naming.cache.dir");
        if (StringUtils.isEmpty(cacheDir)) {
            cacheDir = System.getProperty("user.home") + "/nacos/naming/" + namespace;
        }
    }

    public NacosNamingService(String serverList) {

        this.serverList = serverList;
        init();
        eventDispatcher = new EventDispatcher();
        serverProxy = new NamingProxy(namespace, endpoint, serverList);
        beatReactor = new BeatReactor(serverProxy);
        hostReactor = new HostReactor(eventDispatcher, serverProxy, cacheDir, false);
    }

    public NacosNamingService(Properties properties) {

        init();//初始化一些环境变量配置

        serverList = properties.getProperty(PropertyKeyConst.SERVER_ADDR);//拿一些配置文件信息

        if (StringUtils.isNotEmpty(properties.getProperty(PropertyKeyConst.NAMESPACE))) {
            namespace = properties.getProperty(PropertyKeyConst.NAMESPACE);
        }

        if (StringUtils.isNotEmpty(properties.getProperty(UtilAndComs.NACOS_NAMING_LOG_NAME))) {
            logName = properties.getProperty(UtilAndComs.NACOS_NAMING_LOG_NAME);
        }

        if (StringUtils.isNotEmpty(properties.getProperty(PropertyKeyConst.ENDPOINT))) {
            endpoint = properties.getProperty(PropertyKeyConst.ENDPOINT) + ":" +
                properties.getProperty("address.server.port", "8080");
        }

        cacheDir = System.getProperty("user.home") + "/nacos/naming/" + namespace;//C:\Users\dell\nacos\naming\quickStart

        boolean loadCacheAtStart = false;
        if (StringUtils.isNotEmpty(properties.getProperty(PropertyKeyConst.NAMING_LOAD_CACHE_AT_START))) {
            loadCacheAtStart = BooleanUtils.toBoolean(
                properties.getProperty(PropertyKeyConst.NAMING_LOAD_CACHE_AT_START));
        }

        eventDispatcher = new EventDispatcher();//类似zk的EventThread，用来接收服务端传过来的udp服务报文（watcher）
        serverProxy = new NamingProxy(namespace, endpoint, serverList);//Nacos集群节点刷新，当本地serverList为空时会走流程,5秒钟执行一次
        beatReactor = new BeatReactor(serverProxy);//客户端健康上报,5秒上报一次provider注册的所有dom以及provider运行状态信息
        hostReactor = new HostReactor(eventDispatcher, serverProxy, cacheDir, loadCacheAtStart);//客户端接收服务端节点更变信息推送

    }

    @Override
    public void registerInstance(String serviceName, String ip, int port) throws NacosException {
        registerInstance(serviceName, ip, port, Constants.NAMING_DEFAULT_CLUSTER_NAME);
    }

    @Override
    public void registerInstance(String serviceName, String ip, int port, String clusterName) throws NacosException {
        Instance instance = new Instance();
        instance.setIp(ip);
        instance.setPort(port);
        instance.setWeight(1.0);
        instance.setClusterName(clusterName);

        registerInstance(serviceName, instance);
    }

    @Override
    public void registerInstance(String serviceName, Instance instance) throws NacosException {

        BeatInfo beatInfo = new BeatInfo();
        beatInfo.setDom(serviceName);
        beatInfo.setIp(instance.getIp());
        beatInfo.setPort(instance.getPort());
        beatInfo.setCluster(instance.getClusterName());
        beatInfo.setWeight(instance.getWeight());
        beatInfo.setMetadata(instance.getMetadata());

        beatReactor.addBeatInfo(serviceName, beatInfo);

        serverProxy.registerService(serviceName, instance);
    }

    @Override
    public void deregisterInstance(String serviceName, String ip, int port) throws NacosException {
        deregisterInstance(serviceName, ip, port, Constants.NAMING_DEFAULT_CLUSTER_NAME);
    }

    @Override
    public void deregisterInstance(String serviceName, String ip, int port, String clusterName) throws NacosException {
    	//将服务从dom2Beat中剔除，剔除后“serviceName”接口可以不参与客户端健康上报
    	beatReactor.removeBeatInfo(serviceName, ip, port);
    	//向服务端发起DELETE操作
        serverProxy.deregisterService(serviceName, ip, port, clusterName);
    }

    @Override
    public List<Instance> getAllInstances(String serviceName) throws NacosException {
        return getAllInstances(serviceName, new ArrayList<String>());
    }

    @Override
    public List<Instance> getAllInstances(String serviceName, List<String> clusters) throws NacosException {
    	//获取服务信息（本地+远程）
        ServiceInfo serviceInfo = hostReactor.getServiceInfo(serviceName, StringUtils.join(clusters, ","),
            StringUtils.EMPTY, false);
        List<Instance> list;
        //服务器没有ServiceInfo对应数据时会走该判断返回空列表
        if (serviceInfo == null || CollectionUtils.isEmpty(list = serviceInfo.getHosts())) {
            return new ArrayList<Instance>();
        }
        return list;
    }

    @Override
    public List<Instance> selectInstances(String serviceName, boolean healthyOnly) throws NacosException {
        return selectInstances(serviceName, new ArrayList<String>(), healthyOnly);
    }

    @Override
    public List<Instance> selectInstances(String serviceName, List<String> clusters, boolean healthy)
        throws NacosException {

        ServiceInfo serviceInfo = hostReactor.getServiceInfo(serviceName, StringUtils.join(clusters, ","),
            StringUtils.EMPTY, false);
        List<Instance> list;
        if (serviceInfo == null || CollectionUtils.isEmpty(list = serviceInfo.getHosts())) {
            return new ArrayList<Instance>();
        }

        Iterator<Instance> iterator = list.iterator();
        while (iterator.hasNext()) {
            Instance instance = iterator.next();
            if (healthy != instance.isHealthy() || !instance.isEnabled() || instance.getWeight() <= 0) {
                iterator.remove();
            }
        }

        return list;
    }

    @Override
    public Instance selectOneHealthyInstance(String serviceName) {
        return selectOneHealthyInstance(serviceName, new ArrayList<String>());
    }

    @Override
    public Instance selectOneHealthyInstance(String serviceName, List<String> clusters) {
        return Balancer.RandomByWeight.selectHost(
            hostReactor.getServiceInfo(serviceName, StringUtils.join(clusters, ",")));
    }

    @Override
    public void subscribe(String service, EventListener listener) {//listener参数为用户自定义listener
        eventDispatcher.addListener(hostReactor.getServiceInfo(service, StringUtils.EMPTY), StringUtils.EMPTY,
            listener);
    }

    @Override
    public void subscribe(String service, List<String> clusters, EventListener listener) {
        eventDispatcher.addListener(hostReactor.getServiceInfo(service, StringUtils.join(clusters, ",")),
            StringUtils.join(clusters, ","), listener);
    }

    @Override
    public void unsubscribe(String service, EventListener listener) {
        eventDispatcher.removeListener(service, StringUtils.EMPTY, listener);
    }

    @Override
    public void unsubscribe(String service, List<String> clusters, EventListener listener) {
        eventDispatcher.removeListener(service, StringUtils.join(clusters, ","), listener);
    }

    @Override
    public ListView<String> getServicesOfServer(int pageNo, int pageSize) throws NacosException {
        return serverProxy.getServiceList(pageNo, pageSize);
    }

    @Override
    public ListView<String> getServicesOfServer(int pageNo, int pageSize, AbstractSelector selector) throws NacosException {
        return serverProxy.getServiceList(pageNo, pageSize, selector);
    }

    @Override
    public List<ServiceInfo> getSubscribeServices() {
        return new ArrayList<ServiceInfo>(hostReactor.getServiceInfoMap().values());
    }

    @Override
    public String getServerStatus() {
        return serverProxy.serverHealthy() ? "UP" : "DOWN";
    }

    public BeatReactor getBeatReactor() {
        return beatReactor;
    }
}
