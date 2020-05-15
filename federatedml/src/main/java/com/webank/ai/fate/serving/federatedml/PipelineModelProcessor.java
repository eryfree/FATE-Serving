package com.webank.ai.fate.serving.federatedml;


import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.webank.ai.fate.api.networking.proxy.Proxy;
import com.webank.ai.fate.core.mlmodel.buffer.PipelineProto;
import com.webank.ai.fate.serving.core.bean.*;
import com.webank.ai.fate.serving.core.constant.StatusCode;
import com.webank.ai.fate.serving.core.exceptions.BaseException;
import com.webank.ai.fate.serving.core.exceptions.GuestMergeException;
import com.webank.ai.fate.serving.core.exceptions.HostGetFeatureErrorException;
import com.webank.ai.fate.serving.core.exceptions.RemoteRpcException;
import com.webank.ai.fate.serving.core.model.MergeInferenceAware;
import com.webank.ai.fate.serving.core.model.ModelProcessor;
import com.webank.ai.fate.serving.core.rpc.core.ErrorMessageUtil;
import com.webank.ai.fate.serving.core.rpc.core.FederatedRpcInvoker;
import com.webank.ai.fate.serving.federatedml.model.BaseComponent;
import com.webank.ai.fate.serving.federatedml.model.PrepareRemoteable;
import com.webank.ai.fate.serving.federatedml.model.Returnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.webank.ai.fate.serving.core.bean.Dict.PIPLELINE_IN_MODEL;
import static com.webank.ai.fate.serving.core.rpc.core.ErrorMessageUtil.buildRemoteRpcErrorMsg;
import static com.webank.ai.fate.serving.core.rpc.core.ErrorMessageUtil.transformRemoteErrorCode;

public class PipelineModelProcessor implements ModelProcessor {

    private static final Logger logger = LoggerFactory.getLogger(PipelineModelProcessor.class);
    private static String flower = "pipeline.pipeline:Pipeline";
    private List<BaseComponent> pipeLineNode = new ArrayList<>();
    private Map<String, BaseComponent> componentMap = new HashMap<String, BaseComponent>();
    private DSLParser dslParser = new DSLParser();
    private String modelPackage = "com.webank.ai.fate.serving.federatedml.model";

    @Override
    public BatchInferenceResult guestBatchInference(Context context, BatchInferenceRequest batchInferenceRequest, Map<String, Future> remoteFutureMap, long timeout) {
        BatchInferenceResult batchFederatedResult = new BatchInferenceResult();
        Map<Integer, Map<String, Object>> localResult = batchLocalInference(context, batchInferenceRequest);
        Map<String, BatchInferenceResult> remoteResultMap = Maps.newHashMap();
        remoteFutureMap.forEach((partyId, future) -> {
            Proxy.Packet packet = null;
            try {
                BatchInferenceResult remoteInferenceResult = (BatchInferenceResult) future.get(timeout, TimeUnit.MILLISECONDS);
                if (!StatusCode.SUCCESS.equals(remoteInferenceResult.getRetcode())) {
                    throw new RemoteRpcException(transformRemoteErrorCode(remoteInferenceResult.getRetcode()), buildRemoteRpcErrorMsg(remoteInferenceResult.getRetcode(), remoteInferenceResult.getRetmsg()));
                }
                remoteResultMap.put(partyId, remoteInferenceResult);
            } catch (Exception e) {
                throw new RemoteRpcException("party id " + partyId + " remote error");
            }
        });
        batchFederatedResult = batchMergeHostResult(context, localResult, remoteResultMap);
        return batchFederatedResult;
    }

    /**
     * host 端只需要本地预测即可
     *
     * @param context
     * @param batchHostFederatedParams
     * @return
     */
    @Override
    public BatchInferenceResult hostBatchInference(Context context, BatchHostFederatedParams batchHostFederatedParams) {

        Map<Integer, Map<String, Object>> localResult = batchLocalInference(context, batchHostFederatedParams);
        BatchInferenceResult batchFederatedResult = new BatchInferenceResult();
        localResult.forEach((index, data) -> {
            BatchInferenceResult.SingleInferenceResult singleInferenceResult = new BatchInferenceResult.SingleInferenceResult();
            if (data != null) {
                String retcode = data.get(Dict.RET_CODE) != null ? data.get(Dict.RET_CODE).toString() : StatusCode.SYSTEM_ERROR;
                data.remove(Dict.RET_CODE);
                singleInferenceResult.setData(data);
                singleInferenceResult.setIndex(index);
                singleInferenceResult.setRetcode(retcode);
            }
            batchFederatedResult.getBatchDataList().add(singleInferenceResult);
        });
        batchFederatedResult.setRetcode(StatusCode.SUCCESS);
        return batchFederatedResult;
    }

    /**
     * 目前给  只有sbt 使用到
     *
     * @param context
     * @param inferenceRequest
     * @return
     */
    @Override
    public InferenceRequest guestPrepareDataBeforeInference(Context context, InferenceRequest inferenceRequest) {
        FederatedRpcInvoker federatedRpcInvoker = SpringContextUtil.getBean(FederatedRpcInvoker.class);
        if (inferenceRequest instanceof BatchInferenceRequest) {
            List<BatchInferenceRequest.SingleInferenceData> reqDataList = ((BatchInferenceRequest) inferenceRequest).getBatchDataList();
            reqDataList.forEach(data -> {
                        Map<String, Object> prepareRemoteDataMap = Maps.newHashMap();
                        this.pipeLineNode.forEach(component -> {
                            try {
                                if (component != null && component instanceof PrepareRemoteable) {
                                    PrepareRemoteable prepareRemoteable = (PrepareRemoteable) component;
                                    Map<String, Object> prepareRemoteData = prepareRemoteable.prepareRemoteData(context, data.getSendToRemoteFeatureData());
                                    prepareRemoteDataMap.put(component.getComponentName(), prepareRemoteData);

                                    if (component.getFederatedRpcInvoker() == null) {
                                        component.setFederatedRpcInvoker(federatedRpcInvoker);
                                    }
                                }
                            } catch (Exception e) {
                                // TODO: 2020/3/16   这里需要考虑下异常情况怎么处理
                            }
                        });
                        data.getFeatureData().putAll(prepareRemoteDataMap);
                    }
            );
        } else {
            Map<String, Object> prepareRemoteDataMap = Maps.newHashMap();
            this.pipeLineNode.forEach(component -> {
                try {
                    if (component != null && component instanceof PrepareRemoteable) {
                        PrepareRemoteable prepareRemoteable = (PrepareRemoteable) component;
                        Map<String, Object> prepareRemoteData = prepareRemoteable.prepareRemoteData(context, inferenceRequest.getSendToRemoteFeatureData());
                        prepareRemoteDataMap.put(component.getComponentName(), prepareRemoteData);

                        if (component.getFederatedRpcInvoker() == null) {
                            component.setFederatedRpcInvoker(federatedRpcInvoker);
                        }
                    }
                } catch (Exception e) {
                    // TODO: 2020/3/16   这里需要考虑下异常情况怎么处理
                }
            });
            inferenceRequest.getFeatureData().putAll(prepareRemoteDataMap);
        }
        return null;
    }

    @Override
    public ReturnResult guestInference(Context context, InferenceRequest inferenceRequest, Map<String, Future> futureMap, long timeout) {


        Map<String, Object> localResult = singleLocalPredict(context, inferenceRequest.getFeatureData());
        ReturnResult remoteResult = new ReturnResult();
        Map<String, Object> remoteResultMap = Maps.newHashMap();
        futureMap.forEach((partId, future) -> {
            try {
                ReturnResult remoteReturnResult = (ReturnResult) future.get(timeout, TimeUnit.MILLISECONDS);
                if (remoteReturnResult != null) {
                    Map<String, Object> remoteData = remoteReturnResult.getData();
                    remoteData.put(Dict.RET_CODE, remoteReturnResult.getRetcode());
                    remoteData.put(Dict.MESSAGE, remoteReturnResult.getRetmsg());
                    remoteData.put(Dict.DATA, remoteReturnResult.getData());
                    remoteResultMap.put(partId, remoteData);
                }
            } catch (Exception e) {
                logger.error("host " + partId + " remote error : "+e.getMessage());
                throw new RemoteRpcException("host " + partId + " remote error : "+e.getMessage());
            }
        });
        Map<String, Object> tempResult = singleMerge(context, localResult, remoteResultMap);
        String retcode = tempResult.get(Dict.RET_CODE).toString();
        String message = tempResult.get(Dict.MESSAGE) == null ? "" : tempResult.get(Dict.MESSAGE).toString();
        tempResult.remove(Dict.RET_CODE);
        tempResult.remove(Dict.MESSAGE);
        remoteResult.setData(tempResult);
        remoteResult.setRetcode(retcode);
        remoteResult.setRetmsg(message);
        return remoteResult;
    }

    @Override
    public ReturnResult hostInference(Context context, InferenceRequest InferenceRequest) {
        Map<String, Object> featureData = InferenceRequest.getFeatureData();
        Map<String, Object> returnData = this.singleLocalPredict(context, featureData);
        ReturnResult returnResult = new ReturnResult();
        returnResult.setRetcode(StatusCode.SUCCESS);
        returnResult.setData(returnData);
        return returnResult;
    }

    @Override
    public Object getComponent(String name) {

        return this.componentMap.get(name);
    }

    public int initModel(Map<String, byte[]> modelProtoMap) {
        if (modelProtoMap != null) {
            logger.info("start init pipeline,model components {}", modelProtoMap.keySet());
            try {
                Map<String, byte[]> newModelProtoMap = changeModelProto(modelProtoMap);
                logger.info("after parse pipeline {}", newModelProtoMap.keySet());
                Preconditions.checkArgument(newModelProtoMap.get(PIPLELINE_IN_MODEL) != null);
                PipelineProto.Pipeline pipeLineProto = PipelineProto.Pipeline.parseFrom(newModelProtoMap.get(PIPLELINE_IN_MODEL));
                //inference_dsl
                String dsl = pipeLineProto.getInferenceDsl().toStringUtf8();
                dslParser.parseDagFromDSL(dsl);
                ArrayList<String> components = dslParser.getAllComponent();
                HashMap<String, String> componentModuleMap = dslParser.getComponentModuleMap();

                for (int i = 0; i < components.size(); ++i) {
                    String componentName = components.get(i);
                    String className = componentModuleMap.get(componentName);
                    logger.info("try to get class:{}", className);
                    try {
                        Class modelClass = Class.forName(this.modelPackage + "." + className);
                        BaseComponent mlNode = (BaseComponent) modelClass.getConstructor().newInstance();
                        mlNode.setComponentName(componentName);
                        byte[] protoMeta = newModelProtoMap.get(componentName + ".Meta");
                        byte[] protoParam = newModelProtoMap.get(componentName + ".Param");
                        int returnCode = mlNode.initModel(protoMeta, protoParam);
                        if (returnCode == Integer.valueOf(StatusCode.SUCCESS)) {
                            componentMap.put(componentName, mlNode);
                            pipeLineNode.add(mlNode);
                            logger.info(" add class {} to pipeline task list", className);
                        } else {
                            throw new RuntimeException("init model error");
                        }
                    } catch (Exception ex) {
                        pipeLineNode.add(null);
                        logger.warn("Can not instance {} class", className);
                    }
                }
            } catch (Exception ex) {
                // ex.printStackTrace();
                logger.info("initModel error:{}", ex);
                throw new RuntimeException("initModel error");
            }
            logger.info("Finish init Pipeline");
            return Integer.valueOf(StatusCode.SUCCESS);
        } else {
            logger.error("model content is null ");
            throw new RuntimeException("model content is null");
        }
    }

    public Map<Integer, Map<String, Object>> batchLocalInference(Context context,
                                                                 BatchInferenceRequest batchFederatedParams) {
        List<BatchInferenceRequest.SingleInferenceData> inputList = batchFederatedParams.getBatchDataList();
        Map<Integer, Map<String, Object>> result = new HashMap<>();
        for (int i = 0; i < inputList.size(); i++) {
            BatchInferenceRequest.SingleInferenceData input = inputList.get(i);
            try {

                Map<String, Object> singleResult = singleLocalPredict(context, input.getFeatureData());
                result.put(input.getIndex(), singleResult);
                if (input.isNeedCheckFeature()) {
                    if (input.getFeatureData() == null || input.getFeatureData().size() == 0) {
                        throw new HostGetFeatureErrorException("no feature");
                    }
                }

            } catch (Throwable e) {
                if (result.get(input.getIndex()) == null) {
                    result.put(input.getIndex(), ErrorMessageUtil.handleExceptionToMap(e));
                } else {
                    result.get(input.getIndex()).putAll(ErrorMessageUtil.handleExceptionToMap(e));
                }
            }
        }
        return result;
    }

    private BatchInferenceResult batchMergeHostResult(Context context, Map<Integer, Map<String, Object>> localResult, Map<String, BatchInferenceResult> remoteResult) {

        try {

            Preconditions.checkArgument(localResult != null);
            Preconditions.checkArgument(remoteResult != null);
            BatchInferenceResult batchFederatedResult = new BatchInferenceResult();
            batchFederatedResult.setRetcode(StatusCode.SUCCESS);
            localResult.forEach((index, data) -> {
                Map<String, Object> remoteSingleMap = Maps.newHashMap();
                remoteResult.forEach((partyId, batchResult) -> {
                    if (batchResult.getSingleInferenceResultMap() != null) {
                        if (batchResult.getSingleInferenceResultMap().get(index) != null) {
                            BatchInferenceResult.SingleInferenceResult singleInferenceResult = batchResult.getSingleInferenceResultMap().get(index);
                            Map<String, Object> realRemoteData = singleInferenceResult.getData();
                            realRemoteData.put(Dict.RET_CODE, singleInferenceResult.getRetcode());
                            remoteSingleMap.put(partyId, realRemoteData);
                        }
                    }
                });
                try {
                    Map<String, Object> localData = localResult.get(index);
                    //logger.info("test merge {} : {}",index,remoteSingleMap.size());
                    //logger.info("remote data {}",remoteSingleMap);
                    Map<String, Object> mergeResult = this.singleMerge(context, localData, remoteSingleMap);
                    String retcode = mergeResult.get(Dict.RET_CODE).toString();
                    String msg = mergeResult.get(Dict.MESSAGE) != null ? mergeResult.get(Dict.MESSAGE).toString() : "";
                    mergeResult.remove(Dict.RET_CODE);
                    mergeResult.remove(Dict.MESSAGE);
                    batchFederatedResult.getBatchDataList().add(new BatchInferenceResult.SingleInferenceResult(index, retcode,
                            msg, mergeResult));
                } catch (Exception e) {
                    logger.error("merge remote error", e);
                    String retcode = ErrorMessageUtil.getLocalExceptionCode(e);
                    batchFederatedResult.getBatchDataList().add(new BatchInferenceResult.SingleInferenceResult(index, retcode, e.getMessage(), null));
                }
            });

            return batchFederatedResult;
        } catch (Exception e) {
            throw new GuestMergeException(e.getMessage());
        }
    }

    private boolean checkResult(Map<String, Object> result) {

        if (result == null) {
            return false;
        }
        if (result.get(Dict.RET_CODE) == null) {
            return false;
        }
        String retCode = result.get(Dict.RET_CODE).toString();
        if (!StatusCode.SUCCESS.equals(retCode)) {
            return false;
        }
        return true;
    }

    public Map<String, Object> singleMerge(Context context, Map<String, Object> localData, Map<String, Object> remoteData) {

        if (localData == null || localData.size() == 0) {
            throw new BaseException(StatusCode.GUEST_MERGE_ERROR, "local inference result is null");
        }
        if (remoteData == null || remoteData.size() == 0) {
            throw new BaseException(StatusCode.GUEST_MERGE_ERROR, "remote inference result is null");
        }


        List<Map<String, Object>> outputData = Lists.newArrayList();
        List<Map<String, Object>> tempList = Lists.newArrayList();
        Map<String, Object> result = Maps.newHashMap();
        result.put(Dict.RET_CODE, StatusCode.SUCCESS);
        int pipelineSize = this.pipeLineNode.size();
        for (int i = 0; i < pipelineSize; i++) {
            BaseComponent component = this.pipeLineNode.get(i);
            List<Map<String, Object>> inputs = new ArrayList<>();
            HashSet<Integer> upInputComponents = this.dslParser.getUpInputComponents(i);
            if (upInputComponents != null) {
                Iterator<Integer> iters = upInputComponents.iterator();
                while (iters.hasNext()) {
                    Integer upInput = iters.next();
                    if (upInput == -1) {
                        inputs.add(localData);
                    } else {
                        inputs.add(outputData.get(upInput));
                    }
                }
            } else {
                inputs.add(localData);
            }
            if (component != null) {
                Map<String, Object> mergeResult = null;
                if (component instanceof MergeInferenceAware) {
                    String componentResultKey = component.getComponentName();
                    mergeResult = ((MergeInferenceAware) component).mergeRemoteInference(context, inputs, remoteData);
                    outputData.add(mergeResult);
                    tempList.add(mergeResult);
                } else {

                    outputData.add(inputs.get(0));
                }

                if (component instanceof Returnable && mergeResult != null) {
                    tempList.add(mergeResult);
                }


            } else {
                outputData.add(inputs.get(0));
            }
        }
        if (tempList.size() > 0) {
            result.putAll(tempList.get(tempList.size() - 1));
        }
        return result;


    }

    public Map<String, Object> singleLocalPredict(Context context, Map<String, Object> inputData) {
        List<Map<String, Object>> outputData = Lists.newArrayList();
        List<Map<String, Object>> tempList = Lists.newArrayList();
        Map<String, Object> result = Maps.newHashMap();
        result.put(Dict.RET_CODE, StatusCode.SUCCESS);
        int pipelineSize = this.pipeLineNode.size();
        for (int i = 0; i < pipelineSize; i++) {
            BaseComponent component = this.pipeLineNode.get(i);
            if (logger.isDebugEnabled()) {
                if (component != null) {
                    logger.debug("component class is {}", component.getClass().getName());
                } else {
                    logger.debug("component class is {}", component);
                }
            }
            List<Map<String, Object>> inputs = new ArrayList<>();
            HashSet<Integer> upInputComponents = this.dslParser.getUpInputComponents(i);
            if (upInputComponents != null) {
                Iterator<Integer> iters = upInputComponents.iterator();
                while (iters.hasNext()) {
                    Integer upInput = iters.next();
                    if (upInput == -1) {
                        inputs.add(inputData);
                    } else {
                        inputs.add(outputData.get(upInput));
                    }
                }
            } else {
                inputs.add(inputData);
            }
            if (component != null) {
                Map<String, Object> componentResult = component.localInference(context, inputs);
                outputData.add(componentResult);
                tempList.add(componentResult);
                if (component instanceof Returnable) {
                    result.put(component.getComponentName(), componentResult);
                    if (logger.isDebugEnabled()) {
                        logger.debug("component {} is Returnable return data {}", component, result);
                    }
                }
            } else {
                outputData.add(inputs.get(0));
            }
        }

        return result;


    }

    private LocalInferenceParam buildLocalInferenceParam() {
        LocalInferenceParam param = new LocalInferenceParam();
        return param;
    }

    private HashMap<String, byte[]> changeModelProto(Map<String, byte[]> modelProtoMap) {
        HashMap<String, byte[]> newModelProtoMap = new HashMap<String, byte[]>(8);
        for (Map.Entry<String, byte[]> entry : modelProtoMap.entrySet()) {
            String key = entry.getKey();
            if (!flower.equals(key)) {
                String[] componentNameSegments = key.split("\\.", -1);
                if (componentNameSegments.length != 2) {
                    newModelProtoMap.put(entry.getKey(), entry.getValue());
                    continue;
                }
                if (componentNameSegments[1].endsWith("Meta")) {
                    newModelProtoMap.put(componentNameSegments[0] + ".Meta", entry.getValue());
                } else if (componentNameSegments[1].endsWith("Param")) {
                    newModelProtoMap.put(componentNameSegments[0] + ".Param", entry.getValue());
                }
            } else {
                newModelProtoMap.put(entry.getKey(), entry.getValue());
            }
        }

        return newModelProtoMap;
    }
}
