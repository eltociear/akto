package com.akto.dto.type;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import com.akto.dao.context.Context;
import com.akto.dto.traffic.Key;
import com.akto.dto.traffic.TrafficInfo;
import com.akto.dto.type.SingleTypeInfo.ParamId;
import com.akto.dto.type.SingleTypeInfo.SubType;
import com.akto.dto.type.SingleTypeInfo.SuperType;
import com.akto.dto.type.URLMethods.Method;
import com.akto.util.JSONUtils;
import com.akto.util.Pair;
import com.akto.util.Trie;
import com.akto.util.Trie.Node;
import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RequestTemplate {

    private static class AllParams {
        int lastKnownParamMapSize = 0;
        Set<String> paramNames = new HashSet<>();

        AllParams() {}

        public void rebuild(Set<String> parameterKeys) {
            lastKnownParamMapSize = -1;

            for(String p: parameterKeys) {
                paramNames.add(getParamName(p));
            }
            lastKnownParamMapSize = parameterKeys.size();
        }

        private static String getParamName(String param) {
            String paramName = param.substring(param.lastIndexOf('#')+1);
            int depth = StringUtils.countMatches(param, '#');
            return depth+"-"+paramName;
        }

    }

    private static final Logger logger = LoggerFactory.getLogger(RequestTemplate.class);

    Map<String, KeyTypes> parameters;
    AllParams allParams = new AllParams();
    Map<String, KeyTypes> headers;
    Map<Integer, RequestTemplate> responseTemplates;
    Set<String> userIds = new HashSet<>();
    TrafficRecorder trafficRecorder = new TrafficRecorder();
    Trie keyTrie = new Trie();

    public RequestTemplate() {
    }

    public RequestTemplate(
        Map<String,KeyTypes> parameters, 
        Map<Integer, RequestTemplate> responseTemplates, 
        Map<String,KeyTypes> headers,
        TrafficRecorder trafficRecorder
    ) {
        this.parameters = parameters;
        this.headers = headers;
        this.responseTemplates = responseTemplates;
        this.trafficRecorder = trafficRecorder;
    }

    int mergeTimestamp = 0;

    private void add(Set<String> set, String userId) {
        if (set.size() < 10) set.add(userId);
    }

    public Set<String> getParamNames() {
        Set<String> parameterKeys = parameters.keySet();
        if (parameterKeys.size() != allParams.lastKnownParamMapSize) {
            allParams.rebuild(parameterKeys);
        }
        return allParams.paramNames;
    }
    
    private void insert(Object obj, String userId, Trie.Node<String, Pair<KeyTypes, Set<String>>> root, String url, String method, int responseCode, String prefix, int apiCollectionId, String rawMessage) {

        prefix += ("#"+root.getPathElem());
        if (prefix.startsWith("#")) {
            prefix = prefix.substring(1);
        }

        if (obj instanceof BasicDBObject) {
            BasicDBObject basicDBObject = (BasicDBObject) obj;
            for(String key: basicDBObject.keySet()) {
                Object value = basicDBObject.get(key);
                Trie.Node<String, Pair<KeyTypes, Set<String>>> curr = root.get(key);

                if (curr == null) {
                    curr = root.getOrCreate(key, new Pair<>(new KeyTypes(new HashMap<>(), false), new HashSet<String>()));
                    // logger.info("creating new node for " + key + " in " + url);
                }

                add(curr.getValue().getSecond(), userId);
                insert(value, userId, curr, url, method, responseCode, prefix, apiCollectionId, rawMessage);
            }
        } else if (obj instanceof BasicDBList) {
            for(Object elem: (BasicDBList) obj) {
                Trie.Node<String, Pair<KeyTypes, Set<String>>> listNode = root.getOrCreate("$", new Pair<>(new KeyTypes(new HashMap<>(), false), new HashSet<String>()));
                add(listNode.getValue().getSecond(), userId);
                insert(elem, userId, listNode, url, method, responseCode, prefix, apiCollectionId, rawMessage);
            }
        } else {
            //url, method, responseCode, true, header, value, userId
            root.getValue().getFirst().process(url, method, responseCode, false, prefix, obj, userId, apiCollectionId, rawMessage);
            add(root.getValue().getSecond(), userId);   
        }
    }

    public void processHeaders(Map<String, List<String>> headerPayload, String url, String method, int responseCode, String userId, int apiCollectionId, String rawMessage) {
        for (String header: headerPayload.keySet()) {
            KeyTypes keyTypes = this.headers.get(header);
            if (keyTypes == null) {
                keyTypes = new KeyTypes(new HashMap<>(), false);
                this.headers.put(header, keyTypes);
            }

            for(String value: headerPayload.get(header)) {
                keyTypes.process(url, method, responseCode, true, header, value, userId, apiCollectionId, rawMessage);
            }
        }
    }

    public void processTraffic(int timestamp) {
        trafficRecorder.incr(timestamp);
    }

    public void recordMessage(String message) {
        if (message != null && message.length() > 0) {
            trafficRecorder.recordMessage(message);
        }
    }

    public static long insertTime = 0, processTime = 0, deleteTime = 0;

    public List<SingleTypeInfo> process2(BasicDBObject payload, String url, String method, int responseCode, String userId, int apiCollectionId, String rawMessage) {
            List<SingleTypeInfo> deleted = new ArrayList<>();
        
            if(userIds.size() < 10) userIds.add(userId);

            Trie.Node<String, Pair<KeyTypes, Set<String>>> root = this.keyTrie.getRoot();

            long s = System.currentTimeMillis();
            // insert(payload, userId, root, url, method, responseCode, "", apiCollectionId);
            insertTime += (System.currentTimeMillis() - s);
            int now = Context.now();

            BasicDBObject flattened = JSONUtils.flatten(payload);
            s = System.currentTimeMillis();
            for(String param: flattened.keySet()) {
                if (parameters.size() > 1000) {
                    continue;
                }
                KeyTypes keyTypes = parameters.get(param);
                if (keyTypes == null) {

                    boolean isParentPresent = false;
                    int curr = param.indexOf("#");
                    while (curr < param.length() && curr > 0) {
                        KeyTypes occ = parameters.get(param.substring(0, curr));
                        if (occ != null && occ.occurrences.containsKey(SubType.DICT)) {
                            isParentPresent = true;
                            break;
                        }
                        curr = param.indexOf("#", curr+1);
                    }


                    if (isParentPresent) {
                        continue;
                    } else {
                        keyTypes = new KeyTypes(new HashMap<>(), false);
                        parameters.put(param, keyTypes);    
                    }
                }

                keyTypes.process(url, method, responseCode, false, param, flattened.get(param), userId, apiCollectionId, rawMessage);
            }

            processTime += (System.currentTimeMillis() - s);

            s = System.currentTimeMillis();
            if (now - mergeTimestamp > 60 * 2) {
                deleted = tryMergeNodesInTrie(url, method, responseCode, apiCollectionId);
                mergeTimestamp = now;
            }

            deleteTime += (System.currentTimeMillis() - s);
            return deleted;
    }
    
    public List<SingleTypeInfo> tryMergeNodesInTrie(String url, String method, int responseCode, int apiCollectionId) {
        List<SingleTypeInfo> deletedInfo = new ArrayList<>();
        List<String> mergedNodes = tryMergeNodes(keyTrie.getRoot(), 5, url, method, "", responseCode, apiCollectionId);

        if (mergedNodes == null) {
            mergedNodes = new ArrayList<>();
        }

        for(String prefix: mergedNodes) {
            Iterator<String> iter = parameters.keySet().iterator();

            while(iter.hasNext()) {
                String paramPath = iter.next();
                if (paramPath.startsWith(prefix)) {
                    deletedInfo.addAll(parameters.get(paramPath).getAllTypeInfo());
                    iter.remove();
                }
            }
        }

        keyTrie.flatten(parameters);

        return deletedInfo;
    }

    public void buildTrie() {
        this.keyTrie = new Trie();

        for(String paramPathStr: this.parameters.keySet()) {
            try { 
                String[] paramPath = paramPathStr.split("#");

                Node<String, Pair<KeyTypes, Set<String>>> curr = this.keyTrie.getRoot();
                for (String path: paramPath) {
                    curr = curr.getOrCreate(path, new Pair<>(new KeyTypes(new HashMap<>(), false), new HashSet<>()));
                }

                curr.getValue().setFirst(this.parameters.get(paramPathStr));
                curr.getValue().setSecond(this.parameters.get(paramPathStr).occurrences.values().iterator().next().userIds);
            } catch (Exception e) {
                logger.error("exception in " + paramPathStr, e);
            }

        }
    }

    public Map<String,KeyTypes> getParameters() {
        return this.parameters;
    }

    public void setParameters(Map<String,KeyTypes> parameters) {
        this.parameters = parameters;
    }

    public Set<String> getUserIds() {
        return this.userIds;
    }

    public void setUserIds(Set<String> userIds) {
        this.userIds = userIds;
    }

    public Map<Integer, RequestTemplate> getResponseTemplates() {
        return this.responseTemplates;
    }

    public void setResponseTemplates(Map<Integer, RequestTemplate> responseTemplates) {
        this.responseTemplates = responseTemplates;
    }

    public Map<String,KeyTypes> getHeaders() {
        return this.headers;
    }

    public void setHeaders(Map<String,KeyTypes> headers) {
        this.headers = headers;
    }

    public TrafficRecorder getTrafficRecorder() {
        return this.trafficRecorder;
    }

    public void setTrafficRecorder(TrafficRecorder trafficRecorder) {
        this.trafficRecorder = trafficRecorder;
    }

    public RequestTemplate copy() {
        RequestTemplate ret = new RequestTemplate(new HashMap<>(), new HashMap<>(), new HashMap<>(), new TrafficRecorder(new HashMap<>()));
        
        for(String parameter: parameters.keySet()) {
            ret.parameters.put(parameter, parameters.get(parameter).copy());
        }

        for(String header: headers.keySet()) {
            ret.headers.put(header, headers.get(header).copy());
        }

        if (responseTemplates != null) {
            for(int code: responseTemplates.keySet()) {
                ret.responseTemplates.put(code, responseTemplates.get(code).copy());
            }
        }

        ret.userIds = new HashSet<>();
        ret.userIds.addAll(this.userIds);

        ret.keyTrie = this.keyTrie;
        ret.trafficRecorder = this.trafficRecorder;

        return ret;
    }

    private double varianceDecrease(Set<Node<String, Pair<KeyTypes, Set<String>>>> nodes, int thresh) {

        if (nodes == null || nodes.size() < 2) return -1;

        Set<String> userIds = new HashSet<>();

        for(Node<String, Pair<KeyTypes, Set<String>>> node: nodes) {
            userIds.addAll(node.getValue().getSecond()); 
        }

        if (userIds.size() < thresh) return -1;

        double currVariance = 0.0;

        for(Node<String, Pair<KeyTypes, Set<String>>> node: nodes) {
            currVariance += Math.pow(1 - (node.getValue().getSecond().size()*1.0f)/userIds.size(), 2);
        }

        double mergeVariance = 0;

        return currVariance - mergeVariance;
    }

    private List<String> tryMergeNodesHelper(Node<String, Pair<KeyTypes, Set<String>>> node, int thresh, String url, String method, String prefix, int responseCode, int apiCollectionId) {

        double diff = varianceDecrease(node.getChildren().keySet(), thresh);

        if (diff < 0.5) return null;

        logger.info("flattening trie @" + node.getPathElem() + " in " + url);

        Map<SubType, SingleTypeInfo> occ = new HashMap<>();

        ParamId paramId = new ParamId(url, method, responseCode, false, prefix + "#" + node.getPathElem(), SubType.DICT, apiCollectionId);
        occ.put(SubType.DICT, new SingleTypeInfo(paramId, new HashSet<>(), node.getValue().getSecond(), 1, Context.now(), 0));
        node.getValue().setFirst(new KeyTypes(occ, false));

        node.getChildren().clear();
        List<String> ret = new ArrayList<>();

        ret.add(prefix);
        return ret;
    }

    public List<String> tryMergeNodes(Node<String, Pair<KeyTypes, Set<String>>> node, int thresh, String url, String method, String prefix, int responseCode, int apiCollectionId) {
        prefix += ("#"+node.getPathElem());
        if (prefix.startsWith("#")) {
            prefix = prefix.substring(1);
        }

        List<String> mergedNodes = tryMergeNodesHelper(node, thresh, url, method, prefix, responseCode, apiCollectionId);
        boolean merged = mergedNodes != null && mergedNodes.size() > 0;

        if (!merged && node.getChildren().size() > 0) {
            for(Node<String, Pair<KeyTypes, Set<String>>> child: node.getChildren().keySet()) {
                List<String> childMerges = tryMergeNodes(child, thresh, url, method, prefix, responseCode, apiCollectionId);
                if (childMerges != null) {
                    if (mergedNodes == null) {
                        mergedNodes = childMerges;
                    } else {
                        mergedNodes.addAll(childMerges);
                    }
                }
            }
        }

        return mergedNodes;
    }
 
    @Override
    public String toString() {
        return "{" +
            " parameters='" + getParameters() + "'" +
            ", responseTemplates='" + getResponseTemplates() + "'" +
            ", headers='" + getHeaders() + "'" +
            ", userIds='" + userIds + "'" +
            "}";
    }

    public void mergeFrom(RequestTemplate that) {
        for(String paramName: that.parameters.keySet()) {
            KeyTypes thisKeyTypes = this.parameters.get(paramName);
            KeyTypes thatKeyTypes = that.parameters.get(paramName);
            if (thisKeyTypes == null) {
                this.parameters.put(paramName, thatKeyTypes.copy());
            } else {
                thisKeyTypes.getOccurrences().putAll(thatKeyTypes.getOccurrences());
            }
        }

        for(String header: that.headers.keySet()) {
            KeyTypes thisKeyTypes = this.headers.get(header);
            KeyTypes thatKeyTypes = that.headers.get(header);
            if (thisKeyTypes == null) {
                this.headers.put(header, thatKeyTypes.copy());
            } else {
                thisKeyTypes.getOccurrences().putAll(thatKeyTypes.getOccurrences());
            }
        }


        if (that.responseTemplates != null) {

            for(int statusCode: that.responseTemplates.keySet()) {
                RequestTemplate thisResp = this.responseTemplates.get(statusCode);
                RequestTemplate thatResp = that.responseTemplates.get(statusCode);
                if (thisResp == null) {
                    this.responseTemplates.put(statusCode, thatResp.copy());
                } else {
                    thisResp.mergeFrom(thatResp);
                }
            }
        }

        this.userIds.addAll(that.userIds);

        this.keyTrie.getRoot().mergeFrom(that.keyTrie.getRoot(), MergeTrieKeyFunc.instance);

        this.trafficRecorder.mergeFrom(that.getTrafficRecorder());
    }

    private static class MergeTrieKeyFunc implements BiConsumer<Pair<KeyTypes,Set<String>>,Pair<KeyTypes,Set<String>>> {

        public static final MergeTrieKeyFunc instance = new MergeTrieKeyFunc();

        @Override
        public void accept(Pair<KeyTypes, Set<String>> t, Pair<KeyTypes, Set<String>> u) {
            t.getFirst().getOccurrences().putAll(u.getFirst().getOccurrences());
            t.getSecond().addAll(u.getSecond());
        }
            
    }

    public List<SingleTypeInfo> getAllTypeInfo() {
        List<SingleTypeInfo> ret = new ArrayList<>();

        for(KeyTypes k: parameters.values()) {
            ret.addAll(k.getAllTypeInfo());
        }

        for(KeyTypes k: headers.values()) {
            ret.addAll(k.getAllTypeInfo());
        }

        if (responseTemplates != null) {
            for(RequestTemplate responseParams: responseTemplates.values()) {
                ret.addAll(responseParams.getAllTypeInfo());
            }
        }
        return ret;
    }

    public List<TrafficInfo> removeAllTrafficInfo(int apiCollectionId, String url, Method method, int responseCode) {
        List<TrafficInfo> ret = new ArrayList<>();
        if (!trafficRecorder.isEmpty()) {
            Set<String> hoursSince1970 = trafficRecorder.getTrafficMapSinceLastSync().keySet();
            int start = Integer.parseInt(hoursSince1970.iterator().next())/24/30;
            int end = start + 1;
            TrafficInfo trafficInfo = new TrafficInfo(new Key(apiCollectionId, url, method, responseCode, start, end), trafficRecorder.getTrafficMapSinceLastSync());
            ret.add(trafficInfo);
        }

        trafficRecorder.setTrafficMapSinceLastSync(new HashMap<>());

        if (responseTemplates != null && responseTemplates.size() > 0) {
            for(Map.Entry<Integer, RequestTemplate> entry: responseTemplates.entrySet()) {
                ret.addAll(entry.getValue().removeAllTrafficInfo(apiCollectionId, url, method, entry.getKey()));
            }
        }

        return ret;
    }

    public List<String> removeAllSampleMessage() {
        List<String> ret = new ArrayList<>();
        ret.addAll(trafficRecorder.getSampleMessages().get());
        trafficRecorder.getSampleMessages().get().clear();
        return ret;
    }

    public static boolean isMergedOnStr(URLTemplate urlTemplate) {
        for(SuperType superType: urlTemplate.getTypes()) {
            if (superType == SuperType.STRING) {
                return true;
            }
        }

        return false;
    }

    private static boolean isWithin20Percent(Set<String> xSet, Set<String> ySet) {
        int xs = xSet.size();
        int ys = ySet.size();
        if (xs == 0) return ys == 0;
        if (ys == 0) return xs == 0;

        int allowedDiffs = (int) (0.2 * Math.min(xs, ys));

        if (Math.abs(xs - ys) > allowedDiffs) return false;

        int absentInY = 0;
        for(String x: xSet) {
            if (!ySet.contains(x)) {
                absentInY ++;
                if (absentInY > allowedDiffs) return false;
            }
        }

        allowedDiffs = allowedDiffs - absentInY;

        int absentInX = 0;
        for(String y: ySet) {
            if (!xSet.contains(y)) {
                absentInX ++;
                if (absentInX > allowedDiffs) return false;
            }
        }

        return absentInX <= allowedDiffs;
    }

    private static boolean compareKeys(RequestTemplate a, RequestTemplate b, URLTemplate mergedUrl) {
        if (!isMergedOnStr(mergedUrl)) {
            return true;
        }

        if (!isWithin20Percent(a.getParamNames(), b.getParamNames())) {
            return false;
        }

        for (int aStatus: a.getResponseTemplates().keySet()) {
            if (aStatus >= 200 && aStatus < 300) {
                if (!b.getResponseTemplates().containsKey(aStatus)) {
                    return false;
                }
            }
        }

        for (int bStatus: b.getResponseTemplates().keySet()) {
            if (bStatus >= 200 && bStatus < 300) {
                if (!a.getResponseTemplates().containsKey(bStatus)) {
                    return false;
                }
            }
        }

        boolean has2XXStatus = false;
        for (int aStatus: a.getResponseTemplates().keySet()) {
            if (aStatus >= 200 && aStatus < 300) {
                has2XXStatus = true;
                RequestTemplate aResp = a.getResponseTemplates().get(aStatus);
                RequestTemplate bResp = b.getResponseTemplates().get(aStatus);

                if (aResp == null || bResp == null) {
                    return false;
                }

                if (!isWithin20Percent(aResp.getParamNames(), bResp.getParamNames())) {
                    return false;
                }    
            }
        }

        return has2XXStatus;
    }

    public boolean compare(RequestTemplate that, URLTemplate mergedUrl) {
        return compareKeys(this, that, mergedUrl);
    }
}
