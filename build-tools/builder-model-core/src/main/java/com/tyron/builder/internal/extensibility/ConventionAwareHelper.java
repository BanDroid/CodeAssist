package com.tyron.builder.internal.extensibility;

import groovy.lang.Closure;
import groovy.lang.MissingPropertyException;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import static com.tyron.builder.util.GUtil.uncheckedCall;

import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.internal.ConventionMapping;
import com.tyron.builder.api.internal.HasConvention;
import com.tyron.builder.api.internal.IConventionAware;
import com.tyron.builder.api.plugins.Convention;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.reflect.JavaPropertyReflectionUtil;

@SuppressWarnings("deprecation")
public class ConventionAwareHelper implements ConventionMapping, HasConvention {
    //prefix internal fields with _ so that they don't get into the way of propertyMissing()
    private final Convention _convention;
    private final IConventionAware _source;
    // These are properties that could have convention mapping applied to them
    private final Set<String> _propertyNames;
    // These are properties that should not be allowed to use convention mapping
    private final Set<String> _ineligiblePropertyNames;

    private final Map<String, MappedPropertyImpl> _mappings = new HashMap<String, MappedPropertyImpl>();

    public ConventionAwareHelper(IConventionAware source, Convention convention) {
        this._source = source;
        this._convention = convention;
        this._propertyNames = JavaPropertyReflectionUtil.propertyNames(source);
        this._ineligiblePropertyNames = new HashSet<>();
    }

    private MappedProperty map(String propertyName, MappedPropertyImpl mapping) {
        if (!_propertyNames.contains(propertyName)) {
            throw new InvalidUserDataException(
                "You can't map a property that does not exist: propertyName=" + propertyName);
        }

        if (_ineligiblePropertyNames.contains(propertyName)) {
//            DeprecationLogger.deprecateBehaviour("Using internal convention mapping with a Provider backed property.")
//                    .willBecomeAnErrorInGradle8()
//                    .withUpgradeGuideSection(7, "convention_mapping")
//                    .nagUser();
        }

        _mappings.put(propertyName, mapping);
        return mapping;
    }

    @Override
    public MappedProperty map(String propertyName, final Closure<?> value) {
        return map(propertyName, new MappedPropertyImpl() {
            @Override
            public Object doGetValue(Convention convention, IConventionAware conventionAwareObject) {
                switch (value.getMaximumNumberOfParameters()) {
                    case 0:
                        return value.call();
                    case 1:
                        return value.call(convention);
                    default:
                        return value.call(convention, conventionAwareObject);
                }
            }
        });
    }

    @Override
    public MappedProperty map(String propertyName, final Callable<?> value) {
        return map(propertyName, new MappedPropertyImpl() {
            @Override
            public Object doGetValue(Convention convention, IConventionAware conventionAwareObject) {
                return uncheckedCall(value);
            }
        });
    }

    public void propertyMissing(String name, Object value) {
        if (value instanceof Closure) {
            map(name, Cast.<Closure<?>>uncheckedNonnullCast(value));
        } else {
            throw new MissingPropertyException(name, getClass());
        }
    }

    @Override
    public void ineligible(String propertyName) {
        _ineligiblePropertyNames.add(propertyName);
    }

    @Override
    public <T> T getConventionValue(T actualValue, String propertyName, boolean isExplicitValue) {
        if (isExplicitValue) {
            return actualValue;
        }

        T returnValue = actualValue;
        if (_mappings.containsKey(propertyName)) {
            boolean useMapping = true;
            if (actualValue instanceof Collection && !((Collection<?>) actualValue).isEmpty()) {
                useMapping = false;
            } else if (actualValue instanceof Map && !((Map<?, ?>) actualValue).isEmpty()) {
                useMapping = false;
            }
            if (useMapping) {
                returnValue = Cast.uncheckedNonnullCast(_mappings.get(propertyName).getValue(_convention, _source));
            }
        }
        return returnValue;
    }

    @Override
    public Convention getConvention() {
        return _convention;
    }

    private static abstract class MappedPropertyImpl implements MappedProperty {
        private boolean haveValue;
        private boolean cache;
        private Object cachedValue;

        public Object getValue(Convention convention, IConventionAware conventionAwareObject) {
            if (!cache) {
                return doGetValue(convention, conventionAwareObject);
            }
            if (!haveValue) {
                cachedValue = doGetValue(convention, conventionAwareObject);
                haveValue = true;
            }
            return cachedValue;
        }

        @Override
        public void cache() {
            cache = true;
            cachedValue = null;
        }

        abstract Object doGetValue(Convention convention, IConventionAware conventionAwareObject);
    }
}