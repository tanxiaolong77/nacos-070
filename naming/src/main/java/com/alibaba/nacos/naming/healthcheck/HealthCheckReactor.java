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

import com.alibaba.nacos.naming.misc.Loggers;

import java.util.Map;
import java.util.concurrent.*;

/**
 * @author nacos
 */
public class HealthCheckReactor {

    private static final ScheduledExecutorService EXECUTOR;

    private static Map<String, ScheduledFuture> futureMap = new ConcurrentHashMap<>();

    static {

        int processorCount = Runtime.getRuntime().availableProcessors();
        EXECUTOR
                = Executors
                .newScheduledThreadPool(processorCount <= 1 ? 1 : processorCount / 2, new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread thread = new Thread(r);
                        thread.setDaemon(true);
                        thread.setName("com.alibaba.nacos.naming.health");
                        return thread;
                    }
                });
    }

    public static ScheduledFuture<?> scheduleCheck(HealthCheckTask task) {
        task.setStartTime(System.currentTimeMillis());
        return EXECUTOR.schedule(task, task.getCheckRTNormalized(), TimeUnit.MILLISECONDS);
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
     * 则健康实例数为（N-1）
	 * 
	 * 2、检查实例数
	 * 判断逻辑与上面一样
	 * 则通过调用naming里的api/remvIP4Dom接口将接口从内存中删除掉（在内存中将key对应的datnum.value中的ipAdress剔除）
	 * 批注：在web管理管的《服务列表-实例数》取的是ipAddress的有效个数
	 */
    public static void scheduleCheck(ClientBeatCheckTask task) {
        futureMap.putIfAbsent(task.taskKey(), EXECUTOR.scheduleWithFixedDelay(task, 5000, 5000, TimeUnit.MILLISECONDS));
    }

    public static void cancelCheck(ClientBeatCheckTask task) {
        ScheduledFuture scheduledFuture = futureMap.get(task.taskKey());
        if (scheduledFuture == null) {
            return;
        }
        try {
            scheduledFuture.cancel(true);
        } catch (Exception e) {
            Loggers.EVT_LOG.error("CANCEL-CHECK", "cancel failed!", e);
        }
    }


    public static ScheduledFuture<?> scheduleNow(Runnable task) {
        return EXECUTOR.schedule(task, 0, TimeUnit.MILLISECONDS);
    }
}
