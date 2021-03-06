package com.yzxie.easy.log.engine.push;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.yzxie.easy.log.common.conf.KafkaConfig;
import com.yzxie.easy.log.common.data.bussine.ApiAccessStat;
import com.yzxie.easy.log.common.data.bussine.SecondRequestStat;
import com.yzxie.easy.log.common.data.log.LogType;
import com.yzxie.easy.log.common.kafka.KafkaTopic;
import com.yzxie.easy.log.common.kafka.KafkaTopicPartition;
import com.yzxie.easy.log.engine.bussine.SecondLevelFlow;
import com.yzxie.easy.log.engine.bussine.TopTenApi;
import com.yzxie.easy.log.engine.push.netty.NettyClient;
import com.yzxie.easy.log.engine.push.netty.NettyConstants;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author xieyizun
 * @date 17/11/2018 23:26
 * @description: 定期推送各种日志分析结果给web展示
 */
@Service
public class WebPushService {
    // 每个处理器使用一个nettyClient发送消息给easy web
    private NettyClient nettyClient = new NettyClient(NettyConstants.SERVER_HOST, NettyConstants.SERVER_PORT);
    private KafkaTopic stdOutTopic;

    public WebPushService() {
        Optional<KafkaTopic> kafkaTopicOptional = KafkaConfig.getKafkaTopic(LogType.STDOUT);
        if (kafkaTopicOptional.isPresent()) {
            this.stdOutTopic = kafkaTopicOptional.get();
            // 异步启动netty与服务端的连接，避免服务器还没启动
            ScheduledExecutorService asyncStartUpService = Executors.newSingleThreadScheduledExecutor();
            asyncStartUpService.schedule(new NettyClient.AsyncStartUpTask(nettyClient), 20000, TimeUnit.MILLISECONDS);

            // 每30秒推送一次访问量top10的api
            ScheduledExecutorService topTenApiPush = Executors.newSingleThreadScheduledExecutor();
            topTenApiPush.scheduleAtFixedRate(new PushTopTenApiTask(), 30, 30, TimeUnit.SECONDS);

            // 每30秒推送一次最近300秒的每秒的访问统计
            ScheduledExecutorService secondLevelFlowPush = Executors.newSingleThreadScheduledExecutor();
            secondLevelFlowPush.scheduleAtFixedRate(new PushSecondLevelFlowTask(), 15, 15, TimeUnit.SECONDS);
        }
    }

    private class PushTopTenApiTask implements Runnable {
        @Override
        public void run() {
            JSONArray pushData = new JSONArray();
            for (KafkaTopicPartition partition : stdOutTopic.getPartitions()) {
                String appId = partition.getAppId();
                List<ApiAccessStat> topTenApis = TopTenApi.getTopTenAPis(appId);
                JSONObject data = new JSONObject();
                data.put("logType", stdOutTopic.getName());
                data.put("app", appId);
                data.put("data", topTenApis);
                pushData.add(data);
            }
            JSONObject res = new JSONObject();
            res.put("data", pushData);
            nettyClient.sendMessage(res);
        }
    }

    private class PushSecondLevelFlowTask implements Runnable {
        @Override
        public void run() {
            JSONArray pushData = new JSONArray();
            for (KafkaTopicPartition partition : stdOutTopic.getPartitions()) {
                String appId = partition.getAppId();
                List<SecondRequestStat> secondRequestStats = SecondLevelFlow.getSecondRequestStat(appId);
                if (secondRequestStats != null) {
                    Collections.reverse(secondRequestStats);
                }
                JSONObject data = new JSONObject();
                data.put("logType", stdOutTopic.getName());
                data.put("app", appId);
                data.put("data", secondRequestStats);
                pushData.add(data);
            }
            JSONObject res = new JSONObject();
            res.put("data",pushData);
            nettyClient.sendMessage(res);
        }
    }
}
