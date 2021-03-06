/*
 * Copyright 2008-2009 the original author or authors.
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
package net.hasor.rsf.transform.codec.v1;
import io.netty.buffer.ByteBuf;
import net.hasor.rsf.*;
import net.hasor.rsf.domain.RsfConstants;
import net.hasor.rsf.domain.RsfRuntimeUtils;
import net.hasor.rsf.transform.codec.CodecAdapter;
import net.hasor.rsf.transform.codec.Protocol;
import net.hasor.rsf.transform.protocol.RequestInfo;
import net.hasor.rsf.transform.protocol.ResponseInfo;
import net.hasor.rsf.transform.protocol.v1.PoolBlock;
import net.hasor.rsf.transform.protocol.v1.RequestBlock;
import net.hasor.rsf.transform.protocol.v1.ResponseBlock;
import net.hasor.rsf.utils.ByteStringCachelUtils;
import org.more.util.StringUtils;

import java.io.IOException;
import java.util.List;

import static net.hasor.rsf.domain.RsfConstants.Version_1;
/**
 * Protocol Interface,for custom network protocol
 * @version : 2014年11月4日
 * @author 赵永春(zyc@hasor.net)
 */
public class CodecAdapterForV1 implements CodecAdapter {
    private RsfEnvironment rsfEnvironment = null;
    public CodecAdapterForV1(RsfEnvironment rsfEnvironment) {
        this.rsfEnvironment = rsfEnvironment;
    }
    //
    @Override
    public RequestInfo buildRequestInfo(RsfRequest rsfRequest) throws IOException {
        RequestInfo info = new RequestInfo(Version_1);
        RsfBindInfo<?> rsfBindInfo = rsfRequest.getBindInfo();
        String serializeType = rsfRequest.getSerializeType();
        SerializeCoder coder = rsfEnvironment.getSerializeCoder(serializeType);
        //
        //1.基本信息
        info.setRequestID(rsfRequest.getRequestID());//请求ID
        info.setServiceGroup(rsfBindInfo.getBindGroup());//序列化策略
        info.setServiceName(rsfBindInfo.getBindName());//序列化策略
        info.setServiceVersion(rsfBindInfo.getBindVersion());//序列化策略
        info.setTargetMethod(rsfRequest.getMethod().getName());//序列化策略
        info.setSerializeType(serializeType);//序列化策略
        info.setClientTimeout(rsfRequest.getTimeout());
        info.setMessage(rsfRequest.isMessage());
        //
        //2.params
        Class<?>[] pTypes = rsfRequest.getParameterTypes();
        Object[] pObjects = rsfRequest.getParameterObject();
        for (int i = 0; i < pTypes.length; i++) {
            String typeByte = RsfRuntimeUtils.toAsmType(pTypes[i]);
            byte[] paramByte = coder.encode(pObjects[i]);
            info.addParameter(typeByte, paramByte);
        }
        //
        //3.Opt参数
        info.addOptionMap(rsfRequest);
        //
        return info;
    }
    @Override
    public RequestBlock buildRequestBlock(RequestInfo info) {
        RequestBlock block = new RequestBlock();
        if (info.isMessage()) {
            block.setHead(RsfConstants.RSF_MessageRequest);
        } else {
            block.setHead(RsfConstants.RSF_InvokerRequest);
        }
        //
        //1.基本信息
        block.setRequestID(info.getRequestID());//请求ID
        block.setServiceGroup(pushString(block, info.getServiceGroup()));
        block.setServiceName(pushString(block, info.getServiceName()));
        block.setServiceVersion(pushString(block, info.getServiceVersion()));
        block.setTargetMethod(pushString(block, info.getTargetMethod()));
        block.setSerializeType(pushString(block, info.getSerializeType()));
        block.setClientTimeout(info.getClientTimeout());
        //
        //2.params
        List<String> pTypes = info.getParameterTypes();
        List<byte[]> pValues = info.getParameterValues();
        if ((pTypes != null && !pTypes.isEmpty()) && (pValues != null && !pValues.isEmpty())) {
            for (int i = 0; i < pTypes.size(); i++) {
                String typeKey = pTypes.get(i);
                byte[] valKey = pValues.get(i);
                //
                short paramType = pushString(block, typeKey);
                short paramData = pushBytes(block, valKey);
                block.addParameter(paramType, paramData);
            }
        }
        //
        //3.Opt参数
        String[] optKeys = info.getOptionKeys();
        if (optKeys.length > 0) {
            for (int i = 0; i < optKeys.length; i++) {
                short optKey = pushString(block, optKeys[i]);
                short optVal = pushString(block, info.getOption(optKeys[i]));
                block.addOption(optKey, optVal);
            }
        }
        //
        return block;
    }
    @Override
    public ResponseInfo buildResponseStatus(long requestID, short status, String errorInfo) {
        ResponseInfo info = new ResponseInfo(Version_1);
        info.setRequestID(requestID);
        info.setStatus(status);
        if (StringUtils.isNotBlank(errorInfo)) {
            info.addOption("message", errorInfo);
        }
        return info;
    }
    @Override
    public ResponseInfo buildResponseInfo(RsfResponse rsfResponse) throws IOException {
        ResponseInfo info = new ResponseInfo(Version_1);
        String serializeType = rsfResponse.getSerializeType();
        SerializeCoder coder = rsfEnvironment.getSerializeCoder(serializeType);
        byte[] returnData = coder.encode(rsfResponse.getData());
        info.setRequestID(rsfResponse.getRequestID());
        info.setStatus(rsfResponse.getStatus());
        info.setSerializeType(serializeType);
        info.setReturnData(returnData);
        info.addOptionMap(rsfResponse);
        return info;
    }
    @Override
    public ResponseBlock buildResponseBlock(ResponseInfo info) {
        ResponseBlock block = new ResponseBlock();
        //
        //1.基本信息
        block.setHead(RsfConstants.RSF_Response);
        block.setRequestID(info.getRequestID());//请求ID
        block.setSerializeType(pushString(block, info.getSerializeType()));//序列化策略
        //
        //2.returnData
        block.setReturnData(block.pushData(info.getReturnData()));
        block.setStatus(info.getStatus());//响应状态
        //
        //3.Opt参数
        String[] optKeys = info.getOptionKeys();
        for (String optKey1 : optKeys) {
            short optKey = pushString(block, optKey1);
            short optVal = pushString(block, info.getOption(optKey1));
            block.addOption(optKey, optVal);
        }
        //
        return block;
    }
    //
    /**将字节数据放入，PoolBlock*/
    private static short pushBytes(PoolBlock socketMessage, byte[] attrData) {
        if (attrData != null) {
            return socketMessage.pushData(attrData);
        } else {
            return socketMessage.pushData(null);
        }
    }
    /**将字符串数据放入，PoolBlock*/
    private static short pushString(PoolBlock socketMessage, String attrData) {
        if (attrData != null) {
            return socketMessage.pushData(ByteStringCachelUtils.fromCache(attrData));
        } else {
            return socketMessage.pushData(null);
        }
    }
    //
    //
    private Protocol<RequestBlock>  requestProtocol  = new RpcRequestProtocolV1();
    private Protocol<ResponseBlock> responseProtocol = new RpcResponseProtocolV1();
    @Override
    public void wirteRequestBlock(RequestBlock block, ByteBuf out) throws IOException {
        this.requestProtocol.encode(block, out);
    }
    @Override
    public RequestInfo readRequestInfo(ByteBuf frame) throws IOException {
        RequestBlock rsfBlock = this.requestProtocol.decode(frame);
        RequestInfo info = new RequestInfo(Version_1);
        //
        //1.基本数据
        info.setRequestID(rsfBlock.getRequestID());
        short serializeTypeInt = rsfBlock.getSerializeType();
        String serializeType = ByteStringCachelUtils.fromCache(rsfBlock.readPool(serializeTypeInt));
        info.setSerializeType(serializeType);
        //
        //2.Message
        if (rsfBlock.getHead() == RsfConstants.RSF_InvokerRequest) {
            info.setMessage(false);
        }
        if (rsfBlock.getHead() == RsfConstants.RSF_MessageRequest) {
            info.setMessage(true);
        }
        //
        //3.Opt参数
        int[] optionArray = rsfBlock.getOptions();
        if (optionArray.length > 0) {
            for (int optItem : optionArray) {
                short optKey = (short) (optItem >>> 16);
                short optVal = (short) (optItem & PoolBlock.PoolMaxSize);
                String optKeyStr = ByteStringCachelUtils.fromCache(rsfBlock.readPool(optKey));
                String optValStr = ByteStringCachelUtils.fromCache(rsfBlock.readPool(optVal));
                info.addOption(optKeyStr, optValStr);
            }
        }
        //
        //4.Request
        String serviceGroup = ByteStringCachelUtils.fromCache(rsfBlock.readPool(rsfBlock.getServiceGroup()));
        String serviceName = ByteStringCachelUtils.fromCache(rsfBlock.readPool(rsfBlock.getServiceName()));
        String serviceVersion = ByteStringCachelUtils.fromCache(rsfBlock.readPool(rsfBlock.getServiceVersion()));
        String targetMethod = ByteStringCachelUtils.fromCache(rsfBlock.readPool(rsfBlock.getTargetMethod()));
        int clientTimeout = rsfBlock.getClientTimeout();
        info.setServiceGroup(serviceGroup);
        info.setServiceName(serviceName);
        info.setServiceVersion(serviceVersion);
        info.setTargetMethod(targetMethod);
        info.setClientTimeout(clientTimeout);
        //
        int[] paramDatas = rsfBlock.getParameters();
        if (paramDatas.length > 0) {
            for (int i = 0; i < paramDatas.length; i++) {
                int paramItem = paramDatas[i];
                short paramKey = (short) (paramItem >>> 16);
                short paramVal = (short) (paramItem & PoolBlock.PoolMaxSize);
                byte[] keyData = rsfBlock.readPool(paramKey);
                byte[] valData = rsfBlock.readPool(paramVal);
                //
                String paramType = ByteStringCachelUtils.fromCache(keyData);
                info.addParameter(paramType, valData);
            }
        }
        //
        return info;
    }
    @Override
    public void wirteResponseBlock(ResponseBlock block, ByteBuf out) throws IOException {
        this.responseProtocol.encode(block, out);
    }
    @Override
    public ResponseInfo readResponseInfo(ByteBuf frame) throws IOException {
        ResponseBlock rsfBlock = this.responseProtocol.decode(frame);
        ResponseInfo info = new ResponseInfo(Version_1);
        //
        //1.基本数据
        info.setRequestID(rsfBlock.getRequestID());
        short serializeTypeInt = rsfBlock.getSerializeType();
        String serializeType = ByteStringCachelUtils.fromCache(rsfBlock.readPool(serializeTypeInt));
        info.setSerializeType(serializeType);
        //
        //2.Opt参数
        int[] optionArray = rsfBlock.getOptions();
        for (int optItem : optionArray) {
            short optKey = (short) (optItem >>> 16);
            short optVal = (short) (optItem & PoolBlock.PoolMaxSize);
            String optKeyStr = ByteStringCachelUtils.fromCache(rsfBlock.readPool(optKey));
            String optValStr = ByteStringCachelUtils.fromCache(rsfBlock.readPool(optVal));
            info.addOption(optKeyStr, optValStr);
        }
        //
        //3.Response
        info.setStatus(rsfBlock.getStatus());
        byte[] returnData = rsfBlock.readPool(rsfBlock.getReturnData());
        info.setReturnData(returnData);
        return info;
    }
}
