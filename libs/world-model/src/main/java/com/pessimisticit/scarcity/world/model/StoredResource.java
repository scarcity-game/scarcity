package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On every concrete resource class: Jackson reads the class itself, not
 * {@link Resource}'s deserializer that picks the class;
 * the discriminator keys are written but not set.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@JacksonAnnotationsInside
@JsonDeserialize(using = ValueDeserializer.None.class)
@JsonIgnoreProperties(value = {"resourceKind", "entityKind"}, allowGetters = true)
@interface StoredResource {
}
