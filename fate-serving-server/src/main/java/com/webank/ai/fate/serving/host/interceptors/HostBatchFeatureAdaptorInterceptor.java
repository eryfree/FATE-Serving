package com.webank.ai.fate.serving.host.interceptors;

import com.webank.ai.fate.register.utils.StringUtils;
import com.webank.ai.fate.serving.adaptor.dataaccess.AbstractBatchFeatureDataAdaptor;
import com.webank.ai.fate.serving.common.interceptors.AbstractInterceptor;
import com.webank.ai.fate.serving.core.adaptor.BatchFeatureDataAdaptor;
import com.webank.ai.fate.serving.core.bean.*;
import com.webank.ai.fate.serving.core.constant.StatusCode;
import com.webank.ai.fate.serving.core.exceptions.FeatureDataAdaptorException;
import com.webank.ai.fate.serving.core.exceptions.HostGetFeatureErrorException;
import com.webank.ai.fate.serving.core.rpc.core.InboundPackage;
import com.webank.ai.fate.serving.core.rpc.core.OutboundPackage;
import com.webank.ai.fate.serving.core.utils.InferenceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class HostBatchFeatureAdaptorInterceptor extends AbstractInterceptor<BatchInferenceRequest, BatchInferenceResult> implements InitializingBean {

    Logger logger = LoggerFactory.getLogger(HostBatchFeatureAdaptorInterceptor.class);

    BatchFeatureDataAdaptor batchFeatureDataAdaptor = null;

    @Override
    public void doPreProcess(Context context, InboundPackage<BatchInferenceRequest> inboundPackage, OutboundPackage<BatchInferenceResult> outboundPackage) throws Exception {
        long begin = System.currentTimeMillis();
        if (batchFeatureDataAdaptor == null) {
            throw new FeatureDataAdaptorException("adaptor not found");
        }
        BatchInferenceRequest batchInferenceRequest = inboundPackage.getBody();
        BatchHostFeatureAdaptorResult batchHostFeatureAdaptorResult = batchFeatureDataAdaptor.getFeatures(context, inboundPackage.getBody().getBatchDataList());
        if (batchHostFeatureAdaptorResult == null) {
            throw new HostGetFeatureErrorException("adaptor return null");
        }
        if (!StatusCode.SUCCESS.equals(batchHostFeatureAdaptorResult.getRetcode())) {
            throw new HostGetFeatureErrorException(batchHostFeatureAdaptorResult.getRetcode(), "adaptor return error");
        }
        Map<Integer, BatchHostFeatureAdaptorResult.SingleBatchHostFeatureAdaptorResult> featureResultMap = batchHostFeatureAdaptorResult.getIndexResultMap();
        batchInferenceRequest.getBatchDataList().forEach(request -> {
            request.setNeedCheckFeature(true);
            BatchHostFeatureAdaptorResult.SingleBatchHostFeatureAdaptorResult featureAdaptorResult = featureResultMap.get(request.getIndex());
            if (featureAdaptorResult != null && StatusCode.SUCCESS.equals(featureAdaptorResult.getRetcode()) && featureAdaptorResult.getFeatures() != null) {
                request.setFeatureData(featureAdaptorResult.getFeatures());
            }
        });
        long end = System.currentTimeMillis();
        logger.info("batch adaptor cost {} ", end - begin);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        String adaptorClass = MetaInfo.FEATURE_BATCH_ADAPTOR;
        if (StringUtils.isNotEmpty(adaptorClass)) {
            logger.info("try to load adaptor {}", adaptorClass);
            batchFeatureDataAdaptor = (BatchFeatureDataAdaptor) InferenceUtils.getClassByName(adaptorClass);
            ((AbstractBatchFeatureDataAdaptor) batchFeatureDataAdaptor).setEnvironment(environment);
            try {
                batchFeatureDataAdaptor.init();
            } catch (Exception e) {
                logger.error("batch adaptor init error");
            }
        }
        logger.info("batch adaptor class is {}", adaptorClass);
    }

}
