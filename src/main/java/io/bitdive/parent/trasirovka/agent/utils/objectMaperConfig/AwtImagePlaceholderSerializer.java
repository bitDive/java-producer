package io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.core.type.WritableTypeId;

import java.awt.Image;
import java.io.IOException;

public final class AwtImagePlaceholderSerializer extends StdSerializer<Image> {

    public AwtImagePlaceholderSerializer() {
        super(Image.class);
    }

    private void writeFields(Image img, JsonGenerator gen) throws IOException {
        gen.writeStringField("@Type", "Image");
        gen.writeNumberField("width", img.getWidth(null));
        gen.writeNumberField("height", img.getHeight(null));
    }

    @Override
    public void serialize(Image img, JsonGenerator gen, SerializerProvider provider) throws IOException {
        gen.writeStartObject();
        writeFields(img, gen);
        gen.writeEndObject();
    }

    @Override
    public void serializeWithType(Image img, JsonGenerator gen, SerializerProvider provider, TypeSerializer typeSer)
            throws IOException {
        WritableTypeId typeId = typeSer.writeTypePrefix(gen, typeSer.typeId(img, JsonToken.START_OBJECT));
        writeFields(img, gen);
        typeSer.writeTypeSuffix(gen, typeId);
    }
}

