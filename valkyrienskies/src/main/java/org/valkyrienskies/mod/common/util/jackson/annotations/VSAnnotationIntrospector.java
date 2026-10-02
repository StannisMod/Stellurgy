package org.valkyrienskies.mod.common.util.jackson.annotations;

import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;

public class VSAnnotationIntrospector extends JacksonAnnotationIntrospector {

    /** Effectively final, process lifetime: built once at class initialisation. */
    public static final VSAnnotationIntrospector instance = new VSAnnotationIntrospector();

    private VSAnnotationIntrospector() {}

    @Override
    public boolean hasIgnoreMarker(AnnotatedMember m) {
        if (m.hasAnnotation(PacketIgnore.class)) return true;
        return super.hasIgnoreMarker(m);
    }

}
