package org.proxysoa.spring.service;

import org.proxysoa.spring.exception.SOAControllerCreationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/**
 * The key class which represents mechanizm to obtain controller.
 * It checks available spring application context. If the context has bean for the requested
 * controller the bean is returned.
 * If there is no requested bean locally a Proxy controller is created. The proxy can call controller methods
 * remotely (via Proxy -> RestTemplate call)
 * @link org.proxysoa.spring.service.RestCallHandler
 *
 * ControllerURLResolver is used to get URL for controller's remote call.
 *
 * When enforceProxyCreation=true proxy is created no matter whether local bean exists.
 *
 * @author stanislav.lapitsky created 4/14/2017.
 */
@Component
public class ControllerFactory {

    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private ControllerURLResolver controllerURLResolver;

    //kind of cache which keeps proxy references by class name to avoid permanent Proxy creation
    private Map<String, Object> controllersMap = new HashMap<>();

    //shows whether we need to create proxy or can use local bean (if true proxy is created anyway)
    private boolean enforceProxyCreation = false;

    /**
     * Gets local controller bean (if exists) or creates proxy for the controller remote calls
     * @param controllerInterface controller to be called (wrapped to proxy if necessary)
     * @param <T> class
     * @return local controller instance of Proxy for remote call
     */
    public <T> T getController(Class<T> controllerInterface) {
        if (applicationContext != null) {
            Map<String, ? extends T> beansMap = applicationContext.getBeansOfType(controllerInterface);
            if (enforceProxyCreation || beansMap.size() == 0) {
                return getOrCreateProxy(controllerInterface, controllerURLResolver.getServiceURL(controllerInterface));
            } else if (beansMap.size() > 1) {
                throw new SOAControllerCreationException("Expecting single instance of bean for class "
                        + controllerInterface + " found " + beansMap.size());

            } else {
                return beansMap.entrySet().iterator().next().getValue();
            }
        } else {
            throw new SOAControllerCreationException("ApplicationContext is null");
        }
    }

    /**
     * Checks proxy cache. If enpty creates a new Proxy, stores to the cache and return the proxy instance)
     * @param controllerInterface controller to be called (wrapped to proxy if necessary)
     * @param <T> class
     * @param controllerUrl url for remote calls
     * @return proxy for the controller
     */
    @SuppressWarnings("unchecked")
    private <T> T getOrCreateProxy(Class<T> controllerInterface, String controllerUrl) {
        if (controllerUrl == null) {
            throw new SOAControllerCreationException("Cannot resolve URL for " + controllerInterface.getCanonicalName());
        }
        T controller = (T) controllersMap.get(controllerInterface.getCanonicalName());
        if (controller == null) {
            RestCallHandler restCallHandler = new RestCallHandler(controllerInterface, controllerUrl);
            T proxy = (T) Proxy.newProxyInstance(
                    controllerInterface.getClassLoader(),
                    new Class[]{controllerInterface},
                    restCallHandler);
            controllersMap.put(controllerInterface.getCanonicalName(), proxy);
            controller = proxy;
        }

        return controller;
    }

    public boolean isEnforceProxyCreation() {
        return enforceProxyCreation;
    }

    public void setEnforceProxyCreation(boolean enforceProxyCreation) {
        this.enforceProxyCreation = enforceProxyCreation;
    }
}