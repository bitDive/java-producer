package io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.core.type.WritableTypeId;

import java.awt.image.RenderedImage;
import java.io.IOException;

public final class RenderedImagePlaceholderSerializer extends StdSerializer<RenderedImage> {

    public RenderedImagePlaceholderSerializer() {
        super(RenderedImage.class);
    }

    private void writeFields(RenderedImage img, JsonGenerator gen) throws IOException {
        gen.writeStringField("@Type", "RenderedImage");
        gen.writeNumberField("width", img.getWidth());
        gen.writeNumberField("height", img.getHeight());
        gen.writeNumberField("minX", img.getMinX());
        gen.writeNumberField("minY", img.getMinY());
        // можно добавить bands, tile size и т.п., но аккуратно (без захода в растры)
    }

    @Override
    public void serialize(RenderedImage img, JsonGenerator gen, SerializerProvider provider) throws IOException {
        gen.writeStartObject();
        writeFields(img, gen);
        gen.writeEndObject();
    }

    @Override
    public void serializeWithType(RenderedImage img, JsonGenerator gen, SerializerProvider provider, TypeSerializer typeSer)
            throws IOException {
        WritableTypeId typeId = typeSer.writeTypePrefix(gen, typeSer.typeId(img, JsonToken.START_OBJECT));
        writeFields(img, gen);
        typeSer.writeTypeSuffix(gen, typeId);
    }
}
