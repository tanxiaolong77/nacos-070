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
package com.alibaba.nacos.naming.healthcheck;

import com.alibaba.fastjson.JSON;
import com.alibaba.nacos.naming.boot.RunningConfig;
import com.alibaba.nacos.naming.core.DistroMapper;
import com.alibaba.nacos.naming.core.IpAddress;
import com.alibaba.nacos.naming.core.VirtualClusterDomain;
import com.alibaba.nacos.naming.misc.HttpClient;
import com.alibaba.nacos.naming.misc.Loggers;
import com.alibaba.nacos.naming.misc.UtilsAndCommons;
import com.alibaba.nacos.naming.push.PushService;

import java.net.HttpURLConnection;
import java.util.List;

/**
 * @author <a href="mailto:zpf.073@gmail.com">nkorange</a>
 */
public class ClientBeatCheckTask implements Runnable {
    private VirtualClusterDomain domain;

    public ClientBeatCheckTask(VirtualClusterDomain domain) {
        this.domain = domain;
    }

    public String taskKey() {
        return domain.getName();
    }

    /***
     * 
	 * ###客户端检测线程 ClientBeatCheckTask###
	 * ###五秒执行一次###
	 * 
	 * 核心功能：
	 * 
	 * 判断逻辑：
     * 如果 当前时间 - 最以后一次心跳检测时间 > 默认的节点删除时间（30 * 1000）
	 * 
	 * 1、检查健康实例
	 * ipAddress中的valid会设置为false，然后执行changeDom
	 * 批注：在web管理管的《服务列表-实例健康数》取的是lpAddress的valid为true的数据统计，所以这里如果设置成了false，
     * 则健康实例数（N）这个N就会-1
	 * 
	 * 2、检查实例数
	 * 判断逻辑与上面一样
	 * 则通过调用naming里的api/remvIP4Dom接口将接口从内存中删除掉（在内存中将key对应的datnum.value中的ipAdress剔除）
	 * 批注：在web管理管的《服务列表-实例数》取的是ipAddress的有效个数
	 */
    @Override
    public void run() {
    	/***
    	 * 客户端检测线程
    	 */
        try {
            if (!domain.getEnableClientBeat() || !DistroMapper.responsible(domain.getName())) {
                return;
            }

            List<IpAddress> ipAddresses = domain.allIPs();

            for (IpAddress ipAddress : ipAddresses) {
                if (System.currentTimeMillis() - ipAddress.getLastBeat() > ClientBeatProcessor.CLIENT_BEAT_TIMEOUT) {
                    if (!ipAddress.isMarked()) {
                        if (ipAddress.isValid()) {
                        	/***
                        	 * 在web管理管的《服务列表》取的是lpAddress的valid为true的数据统计，所以这里如果设置成了false，
                        	 * 则健康实例数（N）这个N就会-1
                        	 */
                            ipAddress.setValid(false);
                            Loggers.EVT_LOG.info("{" + ipAddress.getClusterName() + "} {POS} {IP-DISABLED} valid: "
                                    + ipAddress.getIp() + ":" + ipAddress.getPort() + "@" + ipAddress.getClusterName()
                                    + ", region: " + DistroMapper.LOCALHOST_SITE + ", msg: " + "client timeout after "
                                    + ClientBeatProcessor.CLIENT_BEAT_TIMEOUT + ", last beat: " + ipAddress.getLastBeat());
                            PushService.domChanged(domain.getName());
                        }
                    }
                }

                /***
                 * 判断逻辑：
                 * 如果 当前时间+最以后一次心跳检测时间 > 默认的节点删除时间（30 * 1000）
                 * 则通过调用naming里的api/remvIP4Dom接口将接口从内存中删除掉
                 */
                if (System.currentTimeMillis() - ipAddress.getLastBeat() > domain.getIpDeleteTimeout()) {
                    // delete ip
//                    if (domain.allIPs().size() > 1) {
                    Loggers.SRV_LOG.info("[AUTO-DELETE-IP] dom: " + domain.getName() + ", ip: " + JSON.toJSONString(ipAddress));
                    deleteIP(ipAddress);
//                    }
                }
            }
        } catch (Exception e) {
            Loggers.SRV_LOG.warn("Exception while processing client beat time out.", e);
        } finally {
//            HealthCheckReactor.scheduleCheck(this);
        }

    }

    private void deleteIP(IpAddress ipAddress) {
        try {
            String ipList = ipAddress.getIp() + ":" + ipAddress.getPort() + "_"
                    + ipAddress.getWeight() + "_" + ipAddress.getClusterName();
            String url = "http://127.0.0.1:" + RunningConfig.getServerPort() + RunningConfig.getContextPath()
                    + UtilsAndCommons.NACOS_NAMING_CONTEXT + "/api/remvIP4Dom?dom="
                    + domain.getName() + "&ipList=" + ipList + "&token=" + domain.getToken();
            HttpClient.HttpResult result = HttpClient.httpGet(url, null, null);
            if (result.code != HttpURLConnection.HTTP_OK) {
                Loggers.SRV_LOG.error("IP-DEAD", "failed to delete ip automatically, ip: "
                        + ipAddress.toJSON() + ", caused " + result.content + ",resp code: " + result.code);
            }
        } catch (Exception e) {
            Loggers.SRV_LOG.error("IP-DEAD", "failed to delete ip automatically, ip: " + ipAddress.toJSON(), e);
        }

    }
}
