/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.rpc.protocol.dubbo;


import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.ErrorTypeAwareLogger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.serialize.Cleanable;
import org.apache.dubbo.common.serialize.ObjectInput;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.common.utils.CacheableSupplier;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.Codec;
import org.apache.dubbo.remoting.Decodeable;
import org.apache.dubbo.remoting.ExceptionProcessor;
import org.apache.dubbo.remoting.ServiceNotFoundException;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.transport.CodecSupport;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.model.FrameworkServiceRepository;
import org.apache.dubbo.rpc.model.MethodDescriptor;
import org.apache.dubbo.rpc.model.ModuleModel;
import org.apache.dubbo.rpc.model.ProviderModel;
import org.apache.dubbo.rpc.model.ServiceDescriptor;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.apache.dubbo.common.BaseServiceMetadata.keyWithoutGroup;
import static org.apache.dubbo.common.URL.buildKey;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_VERSION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.EXCEPTION_PROCESSOR_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.PROTOCOL_FAILED_DECODE;
import static org.apache.dubbo.rpc.Constants.SERIALIZATION_ID_KEY;
import static org.apache.dubbo.rpc.Constants.SERIALIZATION_SECURITY_CHECK_KEY;

public class DecodeableRpcInvocation extends RpcInvocation implements Codec, Decodeable {

    private static final ErrorTypeAwareLogger log = LoggerFactory.getErrorTypeAwareLogger(DecodeableRpcInvocation.class);

    private Channel channel;

    private byte serializationType;

    private InputStream inputStream;

    private ObjectInput objectInput;

    private Request request;

    private volatile boolean hasDecoded;

    protected final FrameworkModel frameworkModel;

    private Supplier<CallbackServiceCodec> callbackServiceCodecFactory;

    public DecodeableRpcInvocation(FrameworkModel frameworkModel, Channel channel, Request request, InputStream is, byte id) {
        this.frameworkModel = frameworkModel;
        Assert.notNull(channel, "channel == null");
        Assert.notNull(request, "request == null");
        Assert.notNull(is, "inputStream == null");
        this.channel = channel;
        this.request = request;
        this.inputStream = is;
        this.serializationType = id;
        this.callbackServiceCodecFactory = CacheableSupplier.newSupplier(() ->
            new CallbackServiceCodec(frameworkModel));
    }


    public ObjectInput getObjectInput() {
        return objectInput;
    }

    public void setObjectInput(ObjectInput objectInput) {
        this.objectInput = objectInput;
    }

    @Override
    public void decode() throws Exception {
        boolean finishDecode = false;
        if (!hasDecoded && channel != null && inputStream != null) {
            try {
                decode(channel, inputStream);
                finishDecode = true;
            } catch (Throwable e) {
                if (log.isWarnEnabled() && !(e instanceof ServiceNotFoundException)) {
                    log.warn(PROTOCOL_FAILED_DECODE, "", "", "Decode rpc invocation failed: " + e.getMessage(), e);
                }
                request.setBroken(true);
                request.setError(e);
            } finally {
                hasDecoded = true;
                if (finishDecode) {
                    ObjectInput in = getObjectInput();
                    if (in instanceof Cleanable) {
                        ((Cleanable) in).cleanup();
                    }
                }
                // Whether the process ends normally or abnormally (retry at most once), resources must be released
            }
        }
    }

    @Override
    public void retry() throws Exception {

        String exPs = ApplicationModel.defaultModel().getCurrentConfig().getParameters().get(EXCEPTION_PROCESSOR_KEY);
        if (StringUtils.isEmpty(exPs)) {
            return;
        }

        ExtensionLoader<ExceptionProcessor> extensionLoader = ApplicationModel.defaultModel().getDefaultModule().getExtensionLoader(ExceptionProcessor.class);
        ExceptionProcessor expProcessor = extensionLoader.getOrDefaultExtension(exPs);
        expProcessor.setContext(this);

        if (channel != null) {
            try {
                retryDecode(channel, expProcessor);
            } catch (Throwable e) {
                if (log.isWarnEnabled()) {
                    log.warn(PROTOCOL_FAILED_DECODE, "", "", "Decode rpc invocation failed: " + e.getMessage(), e);
                }
                request.setBroken(true);
                request.setError(e);
            } finally {
                expProcessor.cleanUp();
            }
        }
    }

    @Override
    public void encode(Channel channel, OutputStream output, Object message) throws IOException {
        throw new UnsupportedOperationException();
    }

    private void checkSerializationTypeFromRemote() {

    }

    @Override
    public Object decode(Channel channel, InputStream input) throws IOException {
        ObjectInput in = CodecSupport.getSerialization(serializationType)
            .deserialize(channel.getUrl(), input);
        setObjectInput(in);
        this.put(SERIALIZATION_ID_KEY, serializationType);

        String dubboVersion = in.readUTF();
        request.setVersion(dubboVersion);
        setAttachment(DUBBO_VERSION_KEY, dubboVersion);

        String path = in.readUTF();
        setAttachment(PATH_KEY, path);
        String version = in.readUTF();
        setAttachment(VERSION_KEY, version);

        setMethodName(in.readUTF());

        String desc = in.readUTF();
        setParameterTypesDesc(desc);

        ClassLoader originClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            if (Boolean.parseBoolean(System.getProperty(SERIALIZATION_SECURITY_CHECK_KEY, "true"))) {
                CodecSupport.checkSerialization(frameworkModel.getServiceRepository(), path, version, serializationType);
            }
            Object[] args = DubboCodec.EMPTY_OBJECT_ARRAY;
            Class<?>[] pts = DubboCodec.EMPTY_CLASS_ARRAY;
            if (desc.length() > 0) {
                pts = drawPts(path, version, desc, pts);
                if (pts == DubboCodec.EMPTY_CLASS_ARRAY) {
                    if (!RpcUtils.isGenericCall(desc, getMethodName()) && !RpcUtils.isEcho(desc, getMethodName())) {
                        throw new ServiceNotFoundException("Service not found:" + path + ", " + getMethodName());
                    }
                    pts = ReflectUtils.desc2classArray(desc);
                }
                args = drawArgs(in, pts);
            }
            setParameterTypes(pts);

            Map<String, Object> map = in.readAttachments();
            if (CollectionUtils.isNotEmptyMap(map)) {
                addObjectAttachments(map);
            }

            //decode argument ,may be callback
            decodeArgument(channel, pts, args);
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read invocation data failed.", e));
        } finally {
            Thread.currentThread().setContextClassLoader(originClassLoader);
        }
        return this;
    }

    private Object[] drawArgs(ObjectInput in, Class<?>[] pts) throws IOException, ClassNotFoundException {
        Object[] args;
        args = new Object[pts.length];
        for (int i = 0; i < args.length; i++) {
            args[i] = in.readObject(pts[i]);
        }
        return args;
    }


    public void retryDecode(Channel channel, ExceptionProcessor expProcessor) throws IOException {
        ObjectInput in = getObjectInput();
        String path = getAttachment(PATH_KEY);
        String version = getAttachment(VERSION_KEY);
        String desc = getParameterTypesDesc();


        ClassLoader originClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            if (Boolean.parseBoolean(System.getProperty(SERIALIZATION_SECURITY_CHECK_KEY, "true"))) {
                CodecSupport.checkSerialization(frameworkModel.getServiceRepository(), path, version, serializationType);
            }
            Class<?>[] pts = DubboCodec.EMPTY_CLASS_ARRAY;
            Object[] args = DubboCodec.EMPTY_OBJECT_ARRAY;
            if (desc.length() > 0) {
                pts = drawPts(path, version, desc, pts);

                if (pts == DubboCodec.EMPTY_CLASS_ARRAY) {
                    pts = ReflectUtils.desc2classArray(desc);
                }
                args = drawArgs(in, pts);
            }

            if (getParameterTypes() == null) {
                setParameterTypes(pts);
            }
            expProcessor.customPts(pts);

            Map<String, Object> map = in.readAttachments();
            if (CollectionUtils.isNotEmptyMap(map)) {
                expProcessor.customAttachment(map);
                addObjectAttachments(map);
            }

            //decode argument ,may be callback
            decodeArgument(channel, pts, args);

        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read invocation data failed.", e));
        } finally {
            if (in instanceof Cleanable) {
                ((Cleanable) in).cleanup();
            }
            Thread.currentThread().setContextClassLoader(originClassLoader);
        }
    }

    private void decodeArgument(Channel channel, Class<?>[] pts, Object[] args) throws IOException {
        CallbackServiceCodec callbackServiceCodec = callbackServiceCodecFactory.get();
        for (int i = 0; i < args.length; i++) {
            args[i] = callbackServiceCodec.decodeInvocationArgument(channel, this, pts, i, args[i]);
        }

        setArguments(args);
        String targetServiceName = buildKey(getAttachment(PATH_KEY),
            getAttachment(GROUP_KEY),
            getAttachment(VERSION_KEY));
        setTargetServiceUniqueName(targetServiceName);
    }

    private Class<?>[] drawPts(String path, String version, String desc, Class<?>[] pts) {
        FrameworkServiceRepository repository = frameworkModel.getServiceRepository();
        List<ProviderModel> providerModels = repository.lookupExportedServicesWithoutGroup(keyWithoutGroup(path, version));
        ServiceDescriptor serviceDescriptor = null;
        if (CollectionUtils.isNotEmpty(providerModels)) {
            for (ProviderModel providerModel : providerModels) {
                serviceDescriptor = providerModel.getServiceModel();
                if (serviceDescriptor != null) {
                    break;
                }
            }
        }
        if (serviceDescriptor == null) {
            // Unable to find ProviderModel from Exported Services
            for (ApplicationModel applicationModel : frameworkModel.getApplicationModels()) {
                for (ModuleModel moduleModel : applicationModel.getModuleModels()) {
                    serviceDescriptor = moduleModel.getServiceRepository().lookupService(path);
                    if (serviceDescriptor != null) {
                        break;
                    }
                }
            }
        }

        if (serviceDescriptor != null) {
            MethodDescriptor methodDescriptor = serviceDescriptor.getMethod(getMethodName(), desc);
            if (methodDescriptor != null) {
                pts = methodDescriptor.getParameterClasses();
                this.setReturnTypes(methodDescriptor.getReturnTypes());

                // switch TCCL
                if (CollectionUtils.isNotEmpty(providerModels)) {
                    if (providerModels.size() == 1) {
                        Thread.currentThread().setContextClassLoader(providerModels.get(0).getClassLoader());
                    } else {
                        // try all providerModels' classLoader can load pts, use the first one
                        for (ProviderModel providerModel : providerModels) {
                            ClassLoader classLoader = providerModel.getClassLoader();
                            boolean match = true;
                            for (Class<?> pt : pts) {
                                try {
                                    if (!pt.equals(classLoader.loadClass(pt.getName()))) {
                                        match = false;
                                    }
                                } catch (ClassNotFoundException e) {
                                    match = false;
                                }
                            }
                            if (match) {
                                Thread.currentThread().setContextClassLoader(classLoader);
                                break;
                            }
                        }
                    }
                }
            }
        }
        return pts;
    }


    public void resetHasDecoded() {
        this.hasDecoded = false;
    }
}
