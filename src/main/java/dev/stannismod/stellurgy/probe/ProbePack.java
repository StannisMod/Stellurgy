package dev.stannismod.stellurgy.probe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a pack adds to the first probe's record: lines of text, and readings of its own. Only adds — the core
 * record is held apart and is whole without it, so a world with no pack loses nothing the core wrote.
 *
 * <p>The file is {@code firstProbe.json} in the mod's config folder:
 * {@code {"notes": ["..."], "readings": [{"tick": -1000, "gyPerS": 1.2e-5}]}}; both members optional.</p>
 */
public final class ProbePack {

    /** The file's name in the mod's config folder. */
    public static final String FILE = "firstProbe.json";

    /** One reading a pack adds: the tick it was taken at and its dose rate, Gy/s. Immutable. */
    public static final class Reading {
        public final long tick;
        public final double gyPerS;

        Reading(long tick, double gyPerS) {
            this.tick = tick;
            this.gyPerS = gyPerS;
        }
    }

    /**
     * Nothing added: what a world with no pack has; an immutable value. Effectively final, process lifetime:
     * built once at class initialisation.
     */
    public static final ProbePack NONE = new ProbePack(Collections.<String>emptyList(), Collections.<Reading>emptyList());

    private final List<String> notes;
    private final List<Reading> readings;

    private ProbePack(List<String> notes, List<Reading> readings) {
        this.notes = Collections.unmodifiableList(notes);
        this.readings = Collections.unmodifiableList(readings);
    }

    public List<String> notes() {
        return notes;
    }

    public List<Reading> readings() {
        return readings;
    }

    /**
     * The additions a pack file states.
     *
     * @throws JsonParseException for a file that is not the shape above, naming what is wrong with it
     */
    public static ProbePack parse(Reader in) {
        JsonElement root = new JsonParser().parse(in);
        if (!root.isJsonObject()) {
            throw new JsonParseException(FILE + " is not a JSON object");
        }
        JsonObject object = root.getAsJsonObject();
        List<String> notes = new ArrayList<>();
        if (object.has("notes")) {
            for (JsonElement note : array(object, "notes")) {
                notes.add(note.getAsString());
            }
        }
        List<Reading> readings = new ArrayList<>();
        if (object.has("readings")) {
            for (JsonElement reading : array(object, "readings")) {
                JsonObject r = reading.getAsJsonObject();
                if (!r.has("tick") || !r.has("gyPerS")) {
                    throw new JsonParseException(FILE + ": a reading needs both \"tick\" and \"gyPerS\": " + r);
                }
                readings.add(new Reading(r.get("tick").getAsLong(), r.get("gyPerS").getAsDouble()));
            }
        }
        return new ProbePack(notes, readings);
    }

    private static JsonArray array(JsonObject object, String member) {
        JsonElement element = object.get(member);
        if (!element.isJsonArray()) {
            throw new JsonParseException(FILE + ": \"" + member + "\" is not an array");
        }
        return element.getAsJsonArray();
    }

    public NBTTagCompound writeToNBT() {
        NBTTagCompound nbt = new NBTTagCompound();
        NBTTagList noteList = new NBTTagList();
        for (String note : notes) {
            noteList.appendTag(new NBTTagString(note));
        }
        nbt.setTag("notes", noteList);
        NBTTagList readingList = new NBTTagList();
        for (Reading r : readings) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setLong("tick", r.tick);
            tag.setDouble("rate", r.gyPerS);
            readingList.appendTag(tag);
        }
        nbt.setTag("readings", readingList);
        return nbt;
    }

    public static ProbePack readFromNBT(NBTTagCompound nbt) {
        List<String> notes = new ArrayList<>();
        NBTTagList noteList = nbt.getTagList("notes", 8);
        for (int i = 0; i < noteList.tagCount(); i++) {
            notes.add(noteList.getStringTagAt(i));
        }
        List<Reading> readings = new ArrayList<>();
        NBTTagList readingList = nbt.getTagList("readings", 10);
        for (int i = 0; i < readingList.tagCount(); i++) {
            NBTTagCompound tag = readingList.getCompoundTagAt(i);
            readings.add(new Reading(tag.getLong("tick"), tag.getDouble("rate")));
        }
        return new ProbePack(notes, readings);
    }
}
