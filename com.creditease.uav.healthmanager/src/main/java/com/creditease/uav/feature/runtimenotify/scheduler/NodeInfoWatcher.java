/*-
 * <<
 * UAVStack
 * ==
 * Copyright (C) 2016 - 2017 UAVStack
 * ==
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
 * >>
 */

package com.creditease.uav.feature.runtimenotify.scheduler;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.creditease.agent.ConfigurationManager;
import com.creditease.agent.helpers.DataConvertHelper;
import com.creditease.agent.helpers.JSONHelper;
import com.creditease.agent.monitor.api.MonitorDataFrame;
import com.creditease.agent.monitor.api.NotificationEvent;
import com.creditease.agent.spi.AbstractTimerWork;
import com.creditease.agent.spi.AgentFeatureComponent;
import com.creditease.agent.spi.AgentResourceComponent;
import com.creditease.uav.cache.api.CacheManager;
import com.creditease.uav.cache.api.CacheManager.CacheLock;
import com.creditease.uav.feature.RuntimeNotifyCatcher;
import com.creditease.uav.messaging.api.Message;
import com.creditease.uav.messaging.api.MessageProducer;
import com.creditease.uav.messaging.api.MessagingFactory;

/**
 * 
 * NodeInfoWatcher description:
 * 
 * 1. push proc info to cache
 * 
 * 2. judge if there is any proc down
 *
 */
public class NodeInfoWatcher extends AbstractTimerWork {

    private static class CrashEventObj {

        private String ip;
        private String appgroup;
        private int deadProcsCount = 0;
        private List<String> deadProcsInfo = new ArrayList<String>();
        private List<String> deadProcNames = new ArrayList<String>();

        public CrashEventObj(String ip, String appgroup) {
            this.ip = ip;
            this.appgroup = appgroup;
        }

        public String getAppGroup() {

            return appgroup;
        }

        public String getIp() {

            return ip;
        }

        public int getDeadProcsCount() {

            return deadProcsCount;
        }

        public void increDeadProcsCount() {

            deadProcsCount++;
        }

        public void addDeadProcName(String name) {

            this.deadProcNames.add(name);
        }

        public void addDeadProcInfo(String info) {

            this.deadProcsInfo.add(info);
        }

        public String getDeadProcNamesAsString() {

            StringBuffer sb = new StringBuffer("(");
            for (String name : this.deadProcNames) {
                sb.append(name + ",");
            }

            sb = sb.deleteCharAt(sb.length() - 1);

            return sb.append(")").toString();
        }

        public String getDeadProcsInfoAsString() {

            StringBuffer sb = new StringBuffer();
            for (String dpi : deadProcsInfo) {
                sb.append(dpi + "\n");
            }

            return sb.toString();
        }

    }

    private static final String LOCK_KEY = "rtnoitify.nodeinfotimer.lock";
    private static final long LOCK_TIMEOUT = 30 * 1000;
    private static final long DEFAULT_CRASH_TIMEOUT = 5 * 60 * 1000;

    private CacheManager cm;
    private CacheLock lock;
    private int hold;
    private boolean isSendMq;
    private boolean isExchange;

    public NodeInfoWatcher(String cName, String feature) {
        super(cName, feature);

        cm = (CacheManager) getConfigManager().getComponent(this.feature, RuntimeNotifyCatcher.CACHE_MANAGER_NAME);

        lock = cm.newCacheLock("store.region.uav", LOCK_KEY, LOCK_TIMEOUT);

        hold = DataConvertHelper.toInt(getConfigManager().getFeatureConfiguration(feature, "nodeinfotimer.period"),
                15000);
        isSendMq = DataConvertHelper
                .toBoolean(getConfigManager().getFeatureConfiguration(feature, "nodeinfoprocess.sendmq"), true);

        isExchange = DataConvertHelper
                .toBoolean(getConfigManager().getFeatureConfiguration(feature, "nodeinfoprocess.exchange"), false);
    }

    @Override
    public void run() {

        /**
         * Step 1: get node info
         */
        if (!lock.getLock()) {
            return;
        }

        if (log.isTraceEnable()) {
            log.info(this, "NodeInfoWatcher RUN START.");
        }

        Map<String, String> data = cm.getHashAll("store.region.uav", "node.info");

        if (!lock.isLockInHand()) {
            return;
        }

        if (data == null || data.size() == 0) {
            lock.releaseLock();

            if (log.isTraceEnable()) {
                log.info(this, "NodeInfoWatcher RUN END as No Data");
            }

            return;
        }

        if (isFrozen()) {

            if (log.isDebugEnable()) {
                log.debug(this, "NodeInfoWatcher is in frozen time.");
            }

            lock.releaseLock();
            return;
        }

        /**
         * Step 2: sync node info to proc map
         */
        List<Map<String, Object>> mdflist = syncProcInfoToCache(data);

        /**
         * Step 3: check if any proc crash
         */
        judgeProcCrash();

        /**
         * Step 4: push data to runtimenotify mgr or to mq
         */
        if (isExchange) {
            exchangeToRuntimeNotify(JSONHelper.toString(mdflist));

        }

        if (isSendMq) {
            sendToMq(JSONHelper.toString(mdflist));

        }

        freezeTime();

        lock.releaseLock();
    }

    @SuppressWarnings("rawtypes")
    private List<Map<String, Object>> syncProcInfoToCache(Map<String, String> data) {

        List<Map<String, Object>> mdflist = new ArrayList<>();

        Map<String, String> fieldValues = new HashMap<String, String>();

        for (String node : data.values()) {

            Map<String, Object> mdfMap = buildMDF(node);

            MonitorDataFrame mdf = new MonitorDataFrame(mdfMap);

            String time = mdf.getTimeFlag() + "";
            List<Map> els = mdf.getElemInstances("server", "procState");
            for (Map el : els) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) el.get("values");

                String hashKey = genProcHashKey(mdf.getExt("appgroup"), mdf.getIP(), m);

                fieldValues.put(hashKey, time);
            }

            mdflist.add(mdfMap);
        }

        cm.putHash("store.region.uav", "rtnotify.crash.procs", fieldValues);

        if (log.isDebugEnable()) {
            log.debug(this, "NodeInfoWatcher SYNC Node Data to Cache: data size=" + mdflist.size());
        }

        return mdflist;
    }

    /**
     * judgeProcCrash
     */
    private void judgeProcCrash() {

        if (log.isDebugEnable()) {
            log.debug(this, "NodeInfoWatcher Judge Crash START.");
        }

        Map<String, String> allProcs = null;
        try {
            allProcs = cm.getHashAll("store.region.uav", "rtnotify.crash.procs");
        }
        catch (Exception e) {
            log.err(this, "Fail to get all process info", e);
        }

        if (allProcs == null) {
            return;
        }

        String cfgTimeout = getConfigManager().getFeatureConfiguration(feature, "crash.timeout");
        long timeout = DataConvertHelper.toLong(cfgTimeout, DEFAULT_CRASH_TIMEOUT);
        long deadline = System.currentTimeMillis() - timeout;

        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        List<String> dead = new ArrayList<>();

        List<String> deadKeys = new ArrayList<>();

        for (Entry<String, String> en : allProcs.entrySet()) {
            long time = Long.parseLong(en.getValue());

            if (time < deadline) {

                dead.add(format.format(new Date(time)) + "@" + en.getKey());

                deadKeys.add(en.getKey());
            }
        }

        /**
         * Step 3: release lock
         */

        if (dead.isEmpty()) {
            return;
        }

        String[] dKeys = new String[deadKeys.size()];

        deadKeys.toArray(dKeys);

        // delete
        cm.delHash("store.region.uav", "rtnotify.crash.procs", dKeys);

        if (log.isDebugEnable()) {
            log.debug(this, "NodeInfoWatcher Judge Crash RESULT: dead=" + dead.size());
        }

        /**
         * Step 4: there is dead process, make alert
         */
        fireEvent(dead);
    }

    private void fireEvent(List<String> dead) {

        /**
         * Step 1: split crash event by IP
         */
        Map<String, CrashEventObj> ips = new HashMap<String, CrashEventObj>();
        for (String s : dead) {
            String[] info = s.split("@");
            String appgroup = info[1];
            String ip = info[2].substring(0, info[2].indexOf("("));

            String[] procInfo = info[2].split(",");

            String procName = procInfo[1];

            CrashEventObj ceo;

            if (!ips.containsKey(ip)) {
                ceo = new CrashEventObj(ip, appgroup);
                ips.put(ip, ceo);
            }
            else {
                ceo = ips.get(ip);
            }

            ceo.increDeadProcsCount();
            ceo.addDeadProcName(procName);
            ceo.addDeadProcInfo("触发时间：" + info[0] + ",进程信息：" + info[2]);
        }

        /**
         * Step 2: send notification event by IP
         */
        for (CrashEventObj ceo : ips.values()) {

            String title = "应用组[" + ceo.getAppGroup() + "]的" + ceo.getIp() + "共发现" + ceo.getDeadProcsCount() + "进程"
                    + ceo.getDeadProcNamesAsString() + "可疑死掉";
            String description = ceo.getDeadProcsInfoAsString();

            NotificationEvent event = new NotificationEvent(NotificationEvent.EVENT_RT_ALERT_CRASH, title, description);

            /**
             * Notification Manager will not block the event, the frozen time has no effect to this event
             */
            event.addArg(NotificationEvent.EVENT_Tag_NoBlock, "true");
            // add appgroup
            event.addArg("appgroup", ceo.getAppGroup());

            if (log.isTraceEnable()) {
                log.info(this, "NodeInfoWatcher Crash Event Happen: event=" + event.toJSONString());
            }

            this.putNotificationEvent(event);
        }

    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildMDF(String node) {

        Map<String, Object> mdf = new HashMap<>();

        Map<String, Object> nodeMap = JSONHelper.toObject(node, Map.class);
        long time = (long) nodeMap.get("clientTimestamp");
        String ip = (String) nodeMap.get("ip");
        String host = (String) nodeMap.get("host");
        String svrid = (String) nodeMap.get("id");
        String name = (String) nodeMap.get("name");

        mdf.put("time", time);
        mdf.put("host", host);
        mdf.put("ip", ip);
        mdf.put("svrid", svrid + "---" + name);
        mdf.put("tag", "N");

        Map<String, String> ext = new HashMap<String, String>();

        // add appgroup
        ext.put("appgroup", (String) nodeMap.get("group"));

        mdf.put("ext", ext);

        // frames
        Map<String, Object> frames = new HashMap<>();
        // server
        List<Map<String, Object>> servers = new ArrayList<>();
        Map<String, Object> server = null;
        List<Map<String, Object>> instances = null;

        // MEId : "IP"
        server = new HashMap<>();
        server.put("MEId", "hostState");
        // instances
        instances = new ArrayList<>();
        Map<String, Object> infoMap = (Map<String, Object>) nodeMap.get("info");
        Map<String, Object> ins = new HashMap<>();
        ins.put("id", ip + "_");
        putDiskInfo(infoMap);
        ins.put("values", infoMap);
        instances.add(ins);
        server.put("Instances", instances);
        servers.add(server);

        String nodeProcs = (String) infoMap.get("node.procs");
        if (nodeProcs != null) {
            // MEId : "PROC"
            server = new HashMap<>();
            server.put("MEId", "procState");
            // instances
            instances = new ArrayList<>();
            Map<String, Object> procs = JSONHelper.toObject(nodeProcs, Map.class);
            Map<String, Object> values = null;
            for (Map.Entry<String, Object> proc : procs.entrySet()) {
                ins = new HashMap<>();
                values = new HashMap<>();
                Map<String, Object> tmp = (Map<String, Object>) proc.getValue();
                for (Map.Entry<String, Object> e : tmp.entrySet()) {
                    if ("tags".equals(e.getKey())) {
                        values.putAll((Map<String, Object>) e.getValue());
                        continue;
                    }
                    values.put(e.getKey(), e.getValue());
                }
                ins.put("id", ip + "_" + tmp.get("name") + "_" + proc.getKey());
                ins.put("values", values);
                instances.add(ins);
            }
            server.put("Instances", instances);
            servers.add(server);
        }

        frames.put("server", servers);
        mdf.put("frames", frames);

        return mdf;
    }

    // return ip_name_ports_mainjarghash
    private String genProcHashKey(String appgroup, String ip, Map<String, Object> m) {

        String name = (String) m.get("name");

        @SuppressWarnings("unchecked")
        List<String> ports = (List<String>) m.get("ports");
        StringBuilder psb = new StringBuilder();
        for (String port : ports) {
            psb.append(port).append(";");
        }

        String javaInfo = "";

        if ("java".equals(name)) {
            String main = (String) m.get("main");
            // String jflags = (String) m.get("jflags");
            String jargs = m.get("jargs") == null ? "" : (String) m.get("jargs");

            String jarsAbs = (jargs.length() > 100) ? jargs.substring(0, 100) : jargs;

            javaInfo = "," + main + " " + jarsAbs;
        }

        return appgroup + "@" + ip + "(" + psb.toString() + ")," + name + javaInfo;
    }

    //
    @SuppressWarnings("unchecked")
    private void putDiskInfo(Map<String, Object> infoMap) {

        if (infoMap.get("os.io.disk") == null) {
            return;
        }

        String diskStr = (String) infoMap.get("os.io.disk"); // deal windows
        diskStr = diskStr.replace(":\\", "/");
        Map<String, Object> disk = JSONHelper.toObject(diskStr, Map.class);
        for (String dk : disk.keySet()) {
            String pk = dk.replace("/", ".");
            if (!pk.startsWith(".")) {
                pk = "." + pk;
            }
            if (!pk.endsWith(".")) {
                pk = pk + ".";
            }

            Map<String, Object> dv = (Map<String, Object>) disk.get(dk);
            for (String dvk : dv.keySet()) {
                String dvv = dv.get(dvk).toString();
                if ("useRate".equals(dvk)) {
                    dvv = dvv.replace("%", ""); // cut '%'
                }
                infoMap.put("os.io.disk" + pk + dvk, dvv);
            }
        }
    }

    private boolean isFrozen() {

        String timestampStr = cm.get("store.region.uav", "rtnoitify.nodeinfotimer.hold");
        if (timestampStr == null) {
            return false;
        }

        long timestamp = 0;
        try {
            timestamp = Long.parseLong(timestampStr);
        }
        catch (NumberFormatException e) {
            log.err(this, "NodeInfoWatcher timestampStr format Long fail: " + timestampStr, e);
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - timestamp >= hold) {
            return false;
        }
        return true;

    }

    /**
     * freeze time
     * 
     * @return true if freeze success, else return false.
     */
    private void freezeTime() {

        long now = System.currentTimeMillis();
        cm.put("store.region.uav", "rtnoitify.nodeinfotimer.hold", now + "");
    }

    private void sendToMq(String mdfs) {

        AgentResourceComponent arc = (AgentResourceComponent) ConfigurationManager.getInstance()
                .getComponent("messageproducer", "MessageProducerResourceComponent");

        MessageProducer producer = (MessageProducer) arc.getResource();

        if (producer != null) {

            String mesKey = MonitorDataFrame.MessageType.NodeInfo.toString();
            Message msg = MessagingFactory.createMessage(mesKey);
            msg.setParam(mesKey, mdfs);
            boolean check = producer.submit(msg);
            String sendState = mesKey + " Data Sent " + (check ? "SUCCESS" : "FAIL");

            if (log.isDebugEnable()) {
                log.debug(this, sendState + "    " + mdfs);
            }

        }

    }

    private void exchangeToRuntimeNotify(String mdfs) {

        AgentFeatureComponent rn = (AgentFeatureComponent) this.getConfigManager().getComponent("runtimenotify",
                "RuntimeNotifyCatcher");
        if (rn != null) {
            rn.exchange("runtime.notify", mdfs, true);
        }

        if (log.isTraceEnable()) {
            log.info(this, "NodeInfoWatcher RUN END.");
        }

    }
}