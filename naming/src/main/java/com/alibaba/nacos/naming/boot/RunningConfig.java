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
package com.alibaba.nacos.naming.boot;

import com.alibaba.nacos.naming.misc.Loggers;
import com.alibaba.nacos.naming.raft.RaftCore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import javax.servlet.ServletContext;

/**
 * @author nkorange
 */
@Component
public class RunningConfig implements ApplicationListener<WebServerInitializedEvent> {

    private static int serverPort;

    private static String contextPath;

    @Autowired
    private ServletContext servletContext;

    @SuppressFBWarnings("ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD")
    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {

        Loggers.SRV_LOG.info("[SERVER-INIT] got port:" + event.getWebServer().getPort());
        Loggers.SRV_LOG.info("[SERVER-INIT] got path:" + servletContext.getContextPath());

        serverPort = event.getWebServer().getPort();
        contextPath = servletContext.getContextPath();

        try {
        	/**
        	 * nacos配置启动加载入口
        	 * 核心功能：
        	 *  1.开启订阅通知线程
			 *	2.缓存集群节点选举信息列表
			 *	3.从磁盘中读取保存的servers信息加载进内存中
			 *	4.开启选举线程
			 *	5.开启发送心跳线程
			 *	6.开启服务列表检测线程
        	 */
            RaftCore.init();
        } catch (Exception e) {
            Loggers.RAFT.error("VIPSRV-RAFT", "failed to initialize raft sub system", e);
        }
    }

    public static int getServerPort() {
        return serverPort;
    }

    public static String getContextPath() {
        return contextPath;
    }
}
