package org.proxysoa.spring.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.proxysoa.spring.exception.SOAControllerInvocationException;
import org.reflections.ReflectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Wraps Controller and keeps all the remote  REST calls related logic,
 * marshall parameters, remote calls, unmarshall results.
 * On construct creates map of invocation info for each method of Controller.
 * On invoke
 * 1. gets the invocation info from the map,
 * 2. serializes parameters,
 * 3. builds proper Http call parameters
 * 4. calls remote REST service
 * 5. deserializes results to output
 *
 * @author stanislav.lapitsky created 4/14/2017.
 */
public class RestCallHandler implements InvocationHandler {
    private static final Logger LOG = LoggerFactory.getLogger(RestCallHandler.class);

    //key is method name, value is invocation info - request mapping, method etc.
    private Map<String, InvocationInfo> methodInvocationMap = new HashMap<>();

    private HttpHeadersResolver httpHeadersResolver;

    private Class<?> controllerClass;

    /**
     * Constructs invocation info for specified controller interface.
     * Iterates methods storing call info
     *
     * @param controllerClass controller to be called remotely
     * @param controllerUrl   URL of the remote REST web service to be called
     */
    @SuppressWarnings("unchecked")
    public RestCallHandler(Class<?> controllerClass, String controllerUrl, HttpHeadersResolver httpHeadersResolver) {
        this.controllerClass = controllerClass;
        this.httpHeadersResolver = httpHeadersResolver;
        String classMapping = getClassRequestMapping(controllerClass);
        for (Method m : ReflectionUtils.getAllMethods(controllerClass)) {
            storeMethodInfo(m, classMapping, controllerUrl);
        }
    }

    /**
     * Gets calss RequestMapping. Used to build proper URL for remote call
     *
     * @param controllerClass controller to be called remotely
     * @return RequestMapping or empty string if class mapping is not defined
     */
    @SuppressWarnings("unchecked")
    String getClassRequestMapping(Class<?> controllerClass) {
        Set<Annotation> annotations = ReflectionUtils.getAllAnnotations(controllerClass);
        for (Annotation a : annotations) {
            if (a instanceof RequestMapping) {
                if (((RequestMapping) a).value().length > 1) {
                    LOG.warn("More than one request mapping found for {}", controllerClass.getCanonicalName());
                }
                return ((RequestMapping) a).value()[0];
            }
        }
        return "";
    }

    /**
     * Fills and stores method's invocation info (mapping, http method, url, declared parameters)
     *
     * @param m             method to be invoked remotely
     * @param classMapping  mapping of class (used to build full mapping for the method)
     * @param controllerUrl remote webservice URL
     */
    @SuppressWarnings("unchecked")
    private void storeMethodInfo(Method m, String classMapping, String controllerUrl) {
        StringBuilder methodRequestMapping = new StringBuilder(classMapping);
        HttpMethod httpMethod = HttpMethod.GET;
        Set<Annotation> annotations = ReflectionUtils.getAllAnnotations(m);
        List<RequestParam> variables = getRequestParams(m);
        for (Annotation a : annotations) {
            if (a instanceof RequestMapping) {
                if (((RequestMapping) a).value().length > 1) {
                    LOG.warn("More than one request mapping found for {}", controllerClass.getCanonicalName());
                }
                methodRequestMapping.append(((RequestMapping) a).value()[0]);
                httpMethod = HttpMethod.valueOf(((RequestMapping) a).method()[0].name());
            }
        }

        InvocationInfo info = new InvocationInfo(controllerUrl, methodRequestMapping.toString(), httpMethod, variables);
        methodInvocationMap.put(m.getDeclaringClass().getCanonicalName() + ":" + m.getName(), info);
        LOG.debug("InvocationInfo is registered {}", info);
    }

    private List<RequestParam> getRequestParams(Method m) {
        List<RequestParam> params = new ArrayList<>();
        for (Annotation[] paramAnnotations : m.getParameterAnnotations()) {
            for (Annotation a : paramAnnotations) {
                if (a instanceof RequestParam) {
                    params.add((RequestParam) a);
                }
            }
        }

        return params;
    }

    /**
     * Remotely invokes specified method
     *
     * @param proxy  proxy class instance (method owner)
     * @param method method to be called remotely
     * @param args   method parameters' values
     * @return remote call results
     * @throws Throwable throws invocation exceptions
     */
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        LOG.debug("Invoke method {} of {} with args {}",
                method.getName(),
                method.getDeclaringClass().getCanonicalName(),
                Arrays.deepToString(args));
        InvocationInfo info = methodInvocationMap.get(method.getDeclaringClass().getCanonicalName() +
                ":" + method.getName());
        if (info == null) {
            throw new SOAControllerInvocationException("Cannot find invocation info for the method " +
                    method.getName());
        }

        String url = info.serviceUrl + info.requestMapping;
        RestTemplate restTemplate = new RestTemplate();
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url);
        HttpEntity<?> requestEntity = getHttpEntity(args, info, builder);

        ResponseEntity response;
        Type type = method.getGenericReturnType();
        try {
            if (type instanceof ParameterizedType) {
                Assert.isInstanceOf(ParameterizedType.class, type);
                ParameterizedType parameterizedType = (ParameterizedType) type;

                response = restTemplate.exchange(builder.build().encode().toUri(),
                        info.httpMethod,
                        requestEntity,
                        new DeserializeParameterizedTypeReference(parameterizedType));
            } else {
                response = restTemplate.exchange(builder.build().encode().toUri(),
                        info.httpMethod,
                        requestEntity,
                        method.getReturnType());
            }
        } catch (RestClientException e) {
            throw new SOAControllerInvocationException("Error calling remote service URL "
                    + builder.build().encode().toUri(), e);
        }
        return response.getBody();
    }

    /**
     * Create http entity for remote call.
     * Adds headers and call parameters
     *
     * @param args    method arguments' values
     * @param info    invocation info
     * @param builder uri builder
     * @return http entity for RestTemplate call
     */
    private HttpEntity<?> getHttpEntity(Object[] args, InvocationInfo info, UriComponentsBuilder builder) {
        HttpHeaders requestHeaders = new HttpHeaders();
        //if headers resolver is specified use it to get headers
        if (httpHeadersResolver != null) {
            MultiValueMap<String, String> headers = httpHeadersResolver.getHeaders(controllerClass);
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                requestHeaders.put(e.getKey(), e.getValue());
            }
        } else {
            //default accept header when no resolver is found
            requestHeaders.setAccept(Collections.singletonList(new MediaType("application", "json")));
        }

        HttpEntity<?> requestEntity;
        if (info.httpMethod == HttpMethod.GET) {
            builder.queryParams(convertValuesToStrings(getParametersMap(info, args)));
            requestEntity = new HttpEntity<>(requestHeaders);
        } else {
            requestEntity = new HttpEntity<>(getPostBody(info, args), requestHeaders);
        }
        return requestEntity;
    }

    /**
     * Converts Objects values to String values. Used to build URL params for GET calls
     *
     * @param values call parameters map (name/values)
     * @return the same map but with string values
     */
    private MultiValueMap<String, String> convertValuesToStrings(MultiValueMap<String, Object> values) {
        MultiValueMap<String, String> target = new LinkedMultiValueMap<>();
        for (Map.Entry<String, List<Object>> entry : values.entrySet()) {
            List<String> valueList = entry.getValue().stream()
                    .map(obj -> convertToJSON(obj)).collect(Collectors.toList());
            target.put(entry.getKey(), valueList);
        }
        return target;
    }

    /**
     * Creates BODY for POST/PUT etc calls
     *
     * @param ii   invocation info
     * @param args call arguments
     * @return body for REST call
     */
    private Object getPostBody(InvocationInfo ii, Object[] args) {
        if (args.length == 1 && args[0] != null && ClassUtils.isPrimitiveOrWrapper(args[0].getClass())) {
            return args[0];
        }
        return getParametersMap(ii, args);
    }

    /**
     * Using invocation info and call arguments creates and fills a map parameter/value
     * to be sent to REST call.
     * If params and values amount is the same we just pass parameters.
     * if the amounts are different we go through POJO (we suppose POJO values are used)
     * and fill map from the POJO methods
     *
     * @param ii   invocation info
     * @param args call values
     * @return param/value map
     */
    private MultiValueMap<String, Object> getParametersMap(InvocationInfo ii, Object[] args) {
        MultiValueMap<String, Object> variables = new LinkedMultiValueMap<>();
        if (ii.parameters.size() > 0 && ii.parameters.size() == args.length) {
            //we have some declared parameters and exact amount of arguments
            for (int i = 0; i < ii.parameters.size(); i++) {
                Object value = args[i];
                if (value != null) {
                    variables.put(ii.parameters.get(i).value(), Collections.singletonList(value));
                } else if (ii.parameters.get(i).required()) {
                    throw new SOAControllerInvocationException("Cannot resolve value of required parameter " +
                            ii.parameters.get(i).name());
                }
            }

            return variables;
        }

        Map<String, Object> fullMap = getAllArgsMap(args);
        if (ii.parameters.size() > 0) {
            //send declared parameters only
            for (RequestParam param : ii.parameters) {
                Object value = fullMap.get(param.name());
                if (value != null) {
                    variables.put(param.name(), Collections.singletonList(convertToJSON(value)));
                }
            }
        } else {
            //no parameters declared - send all data extracted from pojo params
            for (Map.Entry<String, Object> entry : fullMap.entrySet()) {
                variables.put(entry.getKey(), Collections.singletonList(entry.getValue()));
            }
        }
        return variables;
    }

    /**
     * Gets all pojo fields (field names are param names in the map)
     * and place for each field the field's value
     *
     * @param thingy POJO
     * @return param/value map
     */
    private Map<String, Object> getNonNullProperties(final Object thingy) {
        final Map<String, Object> nonNullProperties = new HashMap<>();
        if (thingy != null && !ClassUtils.isPrimitiveOrWrapper(thingy.getClass())) {
            try {
                final BeanInfo beanInfo = Introspector.getBeanInfo(thingy.getClass());
                for (final PropertyDescriptor descriptor : beanInfo.getPropertyDescriptors()) {
                    final Object propertyValue = descriptor.getReadMethod().invoke(thingy);
                    if (propertyValue != null) {
                        nonNullProperties.put(descriptor.getName(),
                                propertyValue);
                    }
                }
            } catch (IllegalArgumentException | IllegalAccessException
                    | InvocationTargetException | IntrospectionException e) {
                throw new SOAControllerInvocationException("Cannot get property of bean", e);
            }
        }
        return nonNullProperties;
    }

    /**
     * Sum param/value maps in one map for all call values
     *
     * @param args call values
     * @return param/value map
     */
    private Map<String, Object> getAllArgsMap(Object[] args) {
        final Map<String, Object> nonNullProperties = new HashMap<>();
        for (Object obj : args) {
            nonNullProperties.putAll(getNonNullProperties(obj));
        }
        return nonNullProperties;
    }

    /**
     * Converts a value to json
     *
     * @param obj value
     * @return JSON string
     */
    private String convertToJSON(Object obj) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            if (obj == null) {
                return null;
            } else if (obj instanceof String) {
                return (String) obj;
            }
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new SOAControllerInvocationException("Cannot serialize to JSON " + obj, e);
        }
    }

    /**
     * Class keeps invocation info (necessary for REST remote calls)
     */
    static class InvocationInfo {
        // request mapping for the call (added to URL)
        final String requestMapping;
        // http method
        final HttpMethod httpMethod;
        // remote service URL
        final String serviceUrl;
        // api params declared for the method in controller
        final List<RequestParam> parameters;

        /**
         * Constructs invocation info
         *
         * @param serviceUrl     remote URL
         * @param requestMapping method request mapping
         * @param httpMethod     http method
         * @param parameters     declared parameters
         */
        InvocationInfo(String serviceUrl, String requestMapping, HttpMethod httpMethod,
                       List<RequestParam> parameters) {
            this.serviceUrl = serviceUrl;
            this.requestMapping = requestMapping;
            this.httpMethod = httpMethod;
            this.parameters = parameters;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("InvocationInfo{");
            sb.append("requestMapping='").append(requestMapping).append('\'');
            sb.append(", httpMethod=").append(httpMethod);
            sb.append(", serviceUrl='").append(serviceUrl).append('\'');
            sb.append(", parameters=").append(parameters);
            sb.append('}');
            return sb.toString();
        }
    }

}
