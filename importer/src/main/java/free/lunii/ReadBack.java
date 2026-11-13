package free.lunii;

import studio.core.v1.model.AudioAsset;
import studio.core.v1.model.ControlSettings;
import studio.core.v1.model.ImageAsset;
import studio.core.v1.model.StageNode;
import studio.core.v1.model.StoryPack;
import studio.core.v1.model.metadata.StoryPackMetadata;
import studio.core.v1.reader.archive.ArchiveStoryPackReader;
import studio.core.v1.reader.fs.FsStoryPackReader;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the installed pack back with STUdio's own archive and FS readers.
 * This is the check the device transfer does before it ciphers anything.
 */
public final class ReadBack {

    public static void main(String[] args) throws Exception {
        List<String> problems = new ArrayList<>();
        Path library = ImportStory.libraryDir();
        Path zip = null;
        Path folder = null;
        try (var listing = Files.list(library)) {
            for (Path path : listing.toList()) {
                String name = path.getFileName().toString();
                if (name.endsWith(".zip")) {
                    zip = path;
                } else if (Files.isDirectory(path) && name.contains(".converted_")) {
                    folder = path;
                }
            }
        }
        if (zip == null || folder == null) {
            System.err.println("No installed pack in " + library);
            System.exit(1);
        }

        StoryPackMetadata meta;
        try (FileInputStream in = new FileInputStream(zip.toFile())) {
            meta = new ArchiveStoryPackReader().readMetadata(in);
        }
        StoryPack archive;
        try (FileInputStream in = new FileInputStream(zip.toFile())) {
            archive = new ArchiveStoryPackReader().read(in);
        }
        StoryPack device = new FsStoryPackReader().read(folder);

        System.out.println("Archive title: " + meta.getTitle());
        System.out.println("Archive uuid:  " + meta.getUuid());
        System.out.println("FS uuid:       " + device.getUuid());
        System.out.println("Archive nodes: " + archive.getStageNodes().size());
        System.out.println("FS nodes:      " + device.getStageNodes().size());

        expect(problems, meta.getTitle() != null && !meta.getTitle().isBlank(), "archive title");
        expect(problems, meta.getThumbnail() != null && meta.getThumbnail().length > 0, "thumbnail");
        expect(problems, meta.getUuid().equals(device.getUuid()), "archive and FS uuids differ");
        expect(problems, archive.getStageNodes().size() == 2, "archive stage count");
        expect(problems, device.getStageNodes().size() == 2, "FS stage count");

        StageNode cover = device.getStageNodes().get(0);
        StageNode play = device.getStageNodes().get(1);
        checkNode(problems, "cover", cover, true, true, false, false, false);
        checkNode(problems, "play", play, false, false, true, true, true);
        expect(problems, cover.getOkTransition() != null
                && cover.getOkTransition().getActionNode() != null
                && cover.getOkTransition().getActionNode().getOptions().size() == 1
                && cover.getOkTransition().getActionNode().getOptions().get(0) == play,
                "cover OK does not lead to the story");
        expect(problems, play.getOkTransition() == null, "story has an onward link");

        checkImage(problems, cover.getImage());
        expect(problems, play.getImage() == null, "the story keeps an image on screen");
        System.out.println("Story image: none");
        checkAudio(problems, "cover", cover.getAudio(), 1_000);
        checkAudio(problems, "play", play.getAudio(), 1_000_000);

        if (!problems.isEmpty()) {
            problems.forEach(problem -> System.err.println("FAIL " + problem));
            System.exit(1);
        }
        System.out.println("STUdio read the archive and the device pack back.");
    }

    private static void checkNode(List<String> problems, String name, StageNode node,
                                  boolean image, boolean ok, boolean home, boolean pause, boolean autoplay) {
        ControlSettings controls = node.getControlSettings();
        expect(problems, (node.getImage() != null) == image, name + " image");
        expect(problems, node.getAudio() != null, name + " audio");
        expect(problems, !controls.isWheelEnabled(), name + " wheel");
        expect(problems, controls.isOkEnabled() == ok, name + " ok");
        expect(problems, controls.isHomeEnabled() == home, name + " home");
        expect(problems, controls.isPauseEnabled() == pause, name + " pause");
        expect(problems, controls.isAutoJumpEnabled() == autoplay, name + " autoplay");
    }

    private static void checkImage(List<String> problems, ImageAsset image) {
        byte[] data = image.getRawData();
        ByteBuffer header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int width = header.getInt(18);
        int height = header.getInt(22);
        int bits = header.getShort(28) & 0xffff;
        int compression = header.getInt(30);
        System.out.println("Image " + width + "x" + height + " " + bits + "-bit compression " + compression
                + " (" + data.length + " bytes)");
        expect(problems, "image/bmp".equals(image.getMimeType()), "image mime");
        expect(problems, width == 320 && height == 240 && bits == 4 && compression == 2, "image is not 320x240 RLE");
    }

    private static void checkAudio(List<String> problems, String name, AudioAsset audio, int minBytes) throws Exception {
        byte[] data = audio.getRawData();
        AudioFileFormat format = AudioSystem.getAudioFileFormat(new ByteArrayInputStream(data));
        System.out.println(name + " audio " + format.getFormat().getChannels() + " ch "
                + format.getFormat().getSampleRate() + " Hz (" + data.length + " bytes)");
        expect(problems, "audio/mpeg".equals(audio.getMimeType()), name + " audio mime");
        expect(problems, data.length >= minBytes, name + " audio is too small");
        expect(problems, format.getFormat().getChannels() == 1, name + " audio is not mono");
        expect(problems, format.getFormat().getSampleRate() == 44100f, name + " audio is not 44100 Hz");
    }

    private static void expect(List<String> problems, boolean ok, String message) {
        if (!ok) {
            problems.add(message);
        }
    }

    private ReadBack() {
    }
}
