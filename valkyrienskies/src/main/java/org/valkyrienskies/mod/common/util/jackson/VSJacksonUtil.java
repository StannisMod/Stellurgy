package org.valkyrienskies.mod.common.util.jackson;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import org.valkyrienskies.mod.common.ships.ShipData;
import org.valkyrienskies.mod.common.util.jackson.annotations.VSAnnotationIntrospector;

/**
 * Every static field of this type is effectively final, process lifetime: built once at class initialisation.
 */
public class VSJacksonUtil {

    // Built in this class's initialiser, which the JVM runs once and publishes safely to every thread
    // (the physics thread serialises with these too). Configured from code alone and never
    // reconfigured, so each is the same mapper in every lifetime whenever this class happens to load.
    private static final CBORMapper defaultMapper = newDefaultMapper();
    private static final CBORMapper packetMapper = newPacketMapper();

    private static CBORMapper newDefaultMapper() {
        CBORMapper mapper = new CBORMapper();
        configureMapper(mapper);
        return mapper;
    }

    private static CBORMapper newPacketMapper() {
        CBORMapper mapper = new CBORMapper();
        configurePacketMapper(mapper);
        return mapper;
    }

    /**
     * Returns the default mapper for the standard Valkyrien Skies configuration * for serializing
     * things, particularly {@link ShipData}
     */
    public static ObjectMapper getDefaultMapper() {
        return defaultMapper;
    }

    /**
     * Returns the default mapper for Valkyrien Skies network transmissions (e.g., it ignores
     * {@link org.valkyrienskies.mod.common.util.jackson.annotations.PacketIgnore} annotated fields
     */
    public static ObjectMapper getPacketMapper() {
        return packetMapper;
    }

    public static void configurePacketMapper(ObjectMapper mapper) {
        configureMapper(mapper);

        mapper.setAnnotationIntrospector(VSAnnotationIntrospector.instance);
    }

    /**
     * Configures the selected object mapper to use the standard Valkyrien Skies configuration for
     * serializing things, particularly {@link ShipData}
     *
     * @param mapper The ObjectMapper to configure
     */
    public static void configureMapper(ObjectMapper mapper) {
        mapper.registerModule(new MinecraftSerializationModule())
            .registerModule(new JOMLSerializationModule())
            .setVisibility(mapper.getVisibilityChecker()
                .withFieldVisibility(Visibility.ANY)
                .withGetterVisibility(Visibility.NONE)
                .withIsGetterVisibility(Visibility.NONE)
                .withSetterVisibility(Visibility.NONE));
    }

}
