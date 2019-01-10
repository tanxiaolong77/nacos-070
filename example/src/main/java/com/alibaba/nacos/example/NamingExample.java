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
package com.alibaba.nacos.example;

import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.Event;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;

/**
 * @author dungu.zpf
 */
public class NamingExample {
	
	private static String serviceName = "providers:com.fhqb.mnt.service.lottery.MLotteryServiceApi";

	public static void main(String[] args) throws NacosException {
		
		/***
		 * nacos集群说明：
		 * 集群部署方式：
		 * 1、将conf/下的cluster文件键入集群的ip:port（可以部署伪集群，修改nacos端口即可）
		 * 
		 * 2、修改bin/下的start.sh或start.bat中的（可直接复制）
		 * set "JAVA_OPT=%JAVA_OPT% -Dnacos.standalone=false"
		 * rem if not ""%2"" == "cluster" set "JAVA_OPT=%JAVA_OPT% -Dnacos.standalone=true"
		 * 原因：如果不修改standalone策略的话在程序里会默认寻找jvm环境中的nacos.standalone，默认给的是true，
		 * true则为单机版本，不会触发heartBeat以及Election。修改后启动必须需要2个服务都启动的情况下可以使用，这一点和
		 * zk是相同的，只有1台机器的时候无法选举，又因为standalone不是true，所以无法将PeetSet里的leader对象赋值。
		 * 导致如果强行只有1个实例启动时会报NullPointerException，这里的NullPointerException则是因为
		 * PeetSet中的leader对象为空，在代码"StringUtils.equals(leader.ip, ip);"中抛出
		 * 
		 * 3、在zk中只有1个节点是无法运行的，会是not running的状态。在nacos中只有leader一个节点依然是可以运行的,
		 * 但如果2个节点中leader挂掉了，只有一个follow则无法单独运行。在崩溃恢复阶段leader和follow会重新Election
		 * 根据Raft算法重新选出leader，然后在heartBeat线程中与新leader做leader信息数据diff和服务列表（datum）数据diff。
		 * 
		 */

//		new Thread(new Provider()).start();
		new Thread(new Consumer()).start();
		while (true) {
		}
	}

	static class Provider implements Runnable {


		public void run() {
			try {
				Properties properties = new Properties();
				properties.setProperty("serverAddr", "127.0.0.1:8848");//8848=9999,8849=9998
				properties.setProperty("namespace", "quickStart");
				NamingService naming = NamingFactory.createNamingService(properties);
				
				int ipIdx = 0;
				String suf = ".";
				naming.registerInstance(serviceName,"99.99.99.99", 8848, "myCluster");// provider使用
				Thread.sleep(500);
//				while(true){
					try {
//						String ip = (ipIdx++) + suf + ipIdx + suf + ipIdx + suf + ipIdx;

						/***
						 * ###服务端：###
						 * 1.服务端把对应该接口的缓存剔除
						 * 
						 * 2.将naming下服务列表文件中的值
						 * 从：
						 * {"key":"com.alibaba.nacos.naming.iplist.com.sam.service.LoginService","timestamp":1545384008631,"value":"
						 * [{\"app\":\"DEFAULT\",\"clusterName\":\"myCluster\",\"ip\":\"126.126.126.126\",\"lastBeat\":1545384005607,
						 * \"marked\":false,\"metadata\":{},\"port\":8848,\"tenant\":\"\",\"valid\":true,\"weight\":1}]"}
						 * 删除为：
						 * {"key":"com.alibaba.nacos.naming.iplist.com.sam.service.LoginService","timestamp":1545383701416,"value":"[]"}
						 *
						 * 3.通过udpSend将事件推送到各个客户端
						 * 
						 * 4.通过heartBeat机制将数据同步到nacos其他集群节点中
						 *
						 *
						 * ###客户端：###
						 * 1.将本地C:\Users\dell\nacos\naming\quickStart下对应接口的缓存文件中的host清空（"hosts":[]）
						 * 
						 * 2.将缓存中的ServiceInfoMap中对应的接口服务剔除
						 * 
						 */
						naming.deregisterInstance(serviceName,"99.99.99.99", 8848,"myCluster");
					} catch (Exception e) {
					}
//					break;
//				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
	
	static class Consumer implements Runnable {


		public void run() {
			try {
				Properties properties = new Properties();
				properties.setProperty("serverAddr", "127.0.0.1:8848");
				properties.setProperty("namespace", "quickStart");
				/***
				 * 附：在consumer与nacos建立连接时则会将服务端的PublishService中的clientMap追加一个客户端ip，则可以保证
				 * 客户端在subscribe时够接收到服务端发来的配置更变信息推送
				 * 
				 * NamingFactory.createNamingService
				 * 功能点：
				 * 
				 * 1.watcher机制体现在下面的naming.subscribe，
				 * 通过HostReactor线程接收到数据包后塞进本地阻塞队列（在EventDispatcher中体现）
				 * 
        		 * 2.集群节点ip动态监控（用于动态扩容和缩容），在NamingProxy中体现
        		 * 
        		 * 3.客户端健康上报,在BeatReactor中体现
        		 * 
        		 * 4.创建udp客户端接收器,接收服务端节点更变信息推送，在HostReactor线程的PushRecver中体现。
        		 * #####值得注意的是：#####
        		 * provider每注册一次服务都会通过udpSender推送到客户端，
        		 * 但客户端会和nacos传过来的PushPacket做比较，如果PushPacket中包含的服务信息和本地的服务信息一致，
        		 * 则不会将消息推入EventDispatcher的队列中，反之则会推送然后通过回调"onEvent"方法进行后续业务处理
        		 * 
        		 * 4.1.在client启动时会将本地磁盘文件（C:\Users\dell\nacos\naming\quickStart下的服务列表文件）
        		 * 读进缓存map==>serviceInfoMap中，用来保证nacos服务都挂掉时client依然是可用的（此时无法得知服务的状态，
        		 * 例如该接口provider是否已经下线？）
				 */
				NamingService naming = NamingFactory.createNamingService(properties);
				
				/**
				 * subscribe全流程
				 * 1.将“serviceName”推入本地缓存ServiceInfoMap中
				 * 
				 * 2.向nacos服务（https://localhost:8848/nacos/v1/ns/api/srvIPXT）
				 * 获取“serviceName”接口数据后刷新缓存ServiceInfoMap和刷新磁盘文件
				 * C:\Users\dell\nacos\naming\quickStart
				 * 
				 * 3.将当前的serviceInfo add进changedServices中，所以每一次naming.subscribe会默认执行一次
				 * onEvent回调函数，其中要注意的是，每次subscribe操作会将serviceInfo为value，
				 * serviceName为key添加进observerMap中，observerMap主要的作用就是用于记录需要回调的EventListener，
				 * 换言之只有注册进observerMap里的服务才会被回调。
				 * 
				 */
				naming.subscribe(serviceName, new EventListener() {
					@Override
					public void onEvent(Event e) {
						NamingEvent event = ((NamingEvent) e);
						System.out.println("=============> ServiceName："+event.getServiceName()+",instance："+event.getInstances());
					}
				});
				
				while(true){
					/***
					 * getAllInstances执行流程：
					 * 1.判断本地是否有缓存数据，有则直接返回
					 * 
					 * 2.当本地缓存中没有数据时，根据服务名和其他参数作为查询条件去服务端查询
					 * 查询条件可以根据servicename、env、cluster等查询如果想做集群隔离或者环境隔离则
					 * 可以带上后面两个参数即可。
					 * 
					 * 3.当服务器返回空或报错时本地返回空列表
					 * ####重点####
					 * 在返回空列表之前依然会异步的尝试一次调用服务端再次获取接口
					 * 在下面方法中体现：
					 * 		HostReactor的executor.schedule(this, serviceObj.getCacheMillis(), TimeUnit.MILLISECONDS)
					 * ####重点####
					 */
//					System.out.println(naming.getAllInstances(serviceName));// consumer使用
//					System.out.println(naming.getAllInstances("com.sam.service.UserService",Arrays.asList(new String[]{"myCluster2"})));
					Thread.sleep(1000);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
}
