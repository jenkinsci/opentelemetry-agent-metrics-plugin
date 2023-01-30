package io.jenkins.plugins.onmonit;

import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;

public class ONMonitoringEnvironmentConverter implements Converter {

    private static final String NE_COOKIE = "neCookie";
    private static final String OC_COOKIE = "ocCookie";

    private static final String PORT_USED_ATTR = "portUsed";

    private static final String REMOTE_CONFIG_DIR_ATTR = "remoteConfigDir";

    @Override
    public boolean canConvert(@SuppressWarnings("rawtypes") final Class type) {
        return type != null && ONMonitoringEnvironment.class.isAssignableFrom(type);
    }

    @Override
    public void marshal(final Object source, final HierarchicalStreamWriter writer, final MarshallingContext context) {
        final ONMonitoringEnvironment pmEnvironment = (ONMonitoringEnvironment) source;

        writer.addAttribute(NE_COOKIE, pmEnvironment.neCookie);
        writer.addAttribute(OC_COOKIE, pmEnvironment.ocCookie);
        writer.addAttribute(PORT_USED_ATTR, String.valueOf(pmEnvironment.port));
        writer.addAttribute(REMOTE_CONFIG_DIR_ATTR, pmEnvironment.configDir);
    }

    @Override
    public Object unmarshal(final HierarchicalStreamReader reader, final UnmarshallingContext context) {
        final String neCookie = reader.getAttribute(NE_COOKIE);
        final String ocCookie = reader.getAttribute(OC_COOKIE);

        final int portUsed = Integer.parseInt(reader.getAttribute(PORT_USED_ATTR));

        final String configDir = reader.getAttribute(REMOTE_CONFIG_DIR_ATTR);

        final ONMonitoringEnvironment pmEnvironment = new ONMonitoringEnvironment(neCookie, ocCookie, configDir, portUsed, false);

        return pmEnvironment;
    }
}
