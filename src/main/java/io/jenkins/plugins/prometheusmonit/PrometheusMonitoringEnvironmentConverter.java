package io.jenkins.plugins.prometheusmonit;

import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;

public class PrometheusMonitoringEnvironmentConverter implements Converter {

    private static final String COOKIE = "cookie";

    private static final String PORT_USED_ATTR = "portUsed";

    private static final String REMOTE_CONFIG_DIR_ATTR = "remoteConfigDir";

    @Override
    public boolean canConvert(@SuppressWarnings("rawtypes") final Class type) {
        return type != null && PrometheusMonitoringEnvironment.class.isAssignableFrom(type);
    }

    @Override
    public void marshal(final Object source, final HierarchicalStreamWriter writer, final MarshallingContext context) {
        final PrometheusMonitoringEnvironment pmEnvironment = (PrometheusMonitoringEnvironment) source;

        writer.addAttribute(COOKIE, pmEnvironment.cookie);
        writer.addAttribute(PORT_USED_ATTR, String.valueOf(pmEnvironment.port));
        writer.addAttribute(REMOTE_CONFIG_DIR_ATTR, pmEnvironment.configDir);
    }

    @Override
    public Object unmarshal(final HierarchicalStreamReader reader, final UnmarshallingContext context) {
        final String cookie = reader.getAttribute(COOKIE);

        final int portUsed = Integer.parseInt(reader.getAttribute(PORT_USED_ATTR));

        final String configDir = reader.getAttribute(REMOTE_CONFIG_DIR_ATTR);

        final PrometheusMonitoringEnvironment pmEnvironment = new PrometheusMonitoringEnvironment(cookie, configDir, portUsed, false);

        return pmEnvironment;
    }
}
