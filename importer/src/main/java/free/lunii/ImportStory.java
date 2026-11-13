package free.lunii;

import studio.core.v1.model.StoryPack;
import studio.core.v1.reader.archive.ArchiveStoryPackReader;
import studio.core.v1.utils.PackAssetsCompression;
import studio.core.v1.writer.fs.FsStoryPackWriter;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the archive STUdio reads, then runs the same conversion
 * {@code LibraryService.addConvertedFsPackFile} runs, and stores both
 * in the STUdio library.
 */
public final class ImportStory {

    static final int SCREEN_WIDTH = 320;
    static final int SCREEN_HEIGHT = 240;

    public static void main(String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        Path audio = Path.of(arguments.audio);
        Path image = Path.of(arguments.image);
        if (!Files.isRegularFile(audio)) {
            System.err.println("Audio file not found: " + audio.toAbsolutePath());
            System.exit(1);
        }
        if (!Files.isRegularFile(image)) {
            System.err.println("Image file not found: " + image.toAbsolutePath());
            System.exit(1);
        }

        String titleAsset = null;
        byte[] titleBytes = null;
        if (arguments.titleAudio != null) {
            Path titleAudio = Path.of(arguments.titleAudio);
            if (!Files.isRegularFile(titleAudio)) {
                System.err.println("Title audio not found: " + titleAudio.toAbsolutePath());
                System.exit(1);
            }
            titleAsset = assetName("title", titleAudio);
            titleBytes = Files.readAllBytes(titleAudio);
        }

        String title = arguments.title.trim();
        String packUuid = uuid(title + "\0cover");
        byte[] cover = fitCover(image);
        byte[] storyJson = storyJson(title, packUuid, titleAsset).getBytes(StandardCharsets.UTF_8);
        byte[] mp3 = Files.readAllBytes(audio);

        Path temp = Files.createTempDirectory("free-lunii-");
        try {
            Path archive = temp.resolve(packUuid + ".zip");
            writeArchive(archive, storyJson, cover, mp3, titleAsset, titleBytes);
            System.out.println("Reading the archive with STUdio");
            StoryPack storyPack;
            try (FileInputStream in = new FileInputStream(archive.toFile())) {
                storyPack = new ArchiveStoryPackReader().read(in);
            }
            if (!packUuid.equals(storyPack.getUuid())) {
                throw new IllegalStateException(
                        "STUdio read pack uuid " + storyPack.getUuid() + " instead of " + packUuid);
            }

            System.out.println("Converting the picture and the audio with STUdio");
            StoryPack prepared = PackAssetsCompression.withPreparedAssetsFirmware2dot4(storyPack);

            System.out.println("Writing the device pack");
            Path fsRoot = temp.resolve("fs");
            Files.createDirectories(fsRoot);
            Path written = new FsStoryPackWriter().write(prepared, fsRoot);

            Path library = libraryDir();
            Files.createDirectories(library);
            removePrevious(library, packUuid);

            Path zipInLibrary = library.resolve(packUuid + ".zip");
            Files.copy(archive, zipInLibrary);
            String folderName = packUuid + ".converted_" + System.currentTimeMillis();
            Path fsInLibrary = library.resolve(folderName);
            Files.move(written, fsInLibrary);

            FileTime older = FileTime.fromMillis(System.currentTimeMillis() - 2000);
            FileTime newer = FileTime.fromMillis(System.currentTimeMillis());
            Files.setLastModifiedTime(zipInLibrary, older);
            Files.setLastModifiedTime(fsInLibrary, newer);

            System.out.println("Installed \"" + title + "\" in");
            System.out.println("  " + library.toAbsolutePath());
            System.out.println("  " + zipInLibrary.getFileName());
            System.out.println("  " + fsInLibrary.getFileName());
            System.out.println("With STUdio open and the Lunii plugged in, run .\\send.ps1");
        } finally {
            deleteTree(temp);
        }
    }

    static Path libraryDir() {
        String override = System.getProperty("studio.library");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".studio", "library");
    }

    /** Fit the whole picture on the Lunii screen. Empty space is white. */
    static byte[] fitCover(Path image) throws IOException {
        BufferedImage source = ImageIO.read(image.toFile());
        if (source == null) {
            throw new IOException("Could not read the cover image: " + image);
        }
        double scale = Math.min(
                SCREEN_WIDTH / (double) source.getWidth(),
                SCREEN_HEIGHT / (double) source.getHeight());
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage canvas = new BufferedImage(SCREEN_WIDTH, SCREEN_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = canvas.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, (SCREEN_WIDTH - width) / 2, (SCREEN_HEIGHT - height) / 2, width, height, null);
        graphics.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        if (!ImageIO.write(canvas, "png", png)) {
            throw new IOException("Could not write the cover as PNG.");
        }
        return png.toByteArray();
    }

    static String storyJson(String title, String packUuid, String titleAsset) {
        String startId = uuid(title + "\0start");
        String playId = uuid(title + "\0play");
        String quoted = jsonString(title);
        String coverAudio = titleAsset == null ? "null" : "\"" + titleAsset + "\"";
        return """
                {
                  "format": "v1",
                  "title": %s,
                  "description": "",
                  "version": 1,
                  "nightModeAvailable": false,
                  "stageNodes": [
                    {
                      "uuid": "%s",
                      "type": "cover",
                      "name": %s,
                      "image": "cover.png",
                      "audio": %s,
                      "okTransition": { "actionNode": "%s", "optionIndex": 0 },
                      "homeTransition": null,
                      "controlSettings": { "wheel": false, "ok": true, "home": false, "pause": false, "autoplay": false },
                      "position": { "x": 80, "y": 80 },
                      "squareOne": true
                    },
                    {
                      "uuid": "%s",
                      "type": "story",
                      "name": %s,
                      "image": null,
                      "audio": "story.mp3",
                      "okTransition": null,
                      "homeTransition": null,
                      "controlSettings": { "wheel": false, "ok": false, "home": true, "pause": true, "autoplay": true },
                      "position": { "x": 520, "y": 80 }
                    }
                  ],
                  "actionNodes": [
                    {
                      "id": "%s",
                      "type": "action",
                      "name": "Start",
                      "options": ["%s"],
                      "position": { "x": 300, "y": 80 }
                    }
                  ]
                }
                """.formatted(quoted, packUuid, quoted, coverAudio, startId, playId, quoted, startId, playId);
    }

    /** STUdio accepts MP3, WAV, and Ogg, and resamples them for the device. */
    static String assetName(String stem, Path file) {
        String filename = file.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        String extension = dot < 0 ? "" : filename.substring(dot).toLowerCase(Locale.ROOT);
        if (!extension.equals(".mp3") && !extension.equals(".wav") && !extension.equals(".ogg")) {
            throw new IllegalArgumentException("Title audio must be an MP3, WAV, or Ogg file: " + filename);
        }
        return stem + extension;
    }

    private static void writeArchive(Path zip, byte[] storyJson, byte[] cover, byte[] mp3,
                                     String titleAsset, byte[] titleAudio) throws IOException {
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream archive = new ZipOutputStream(out)) {
            store(archive, "story.json", storyJson);
            store(archive, "thumbnail.png", cover);
            store(archive, "assets/cover.png", cover);
            if (titleAudio != null) {
                store(archive, "assets/" + titleAsset, titleAudio);
            }
            store(archive, "assets/story.mp3", mp3);
        }
    }

    private static void store(ZipOutputStream archive, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        CRC32 crc = new CRC32();
        crc.update(data);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        entry.setCompressedSize(data.length);
        entry.setCrc(crc.getValue());
        archive.putNextEntry(entry);
        archive.write(data);
        archive.closeEntry();
    }

    private static void removePrevious(Path library, String packUuid) throws IOException {
        try (var listing = Files.list(library)) {
            for (Path path : listing.toList()) {
                String name = path.getFileName().toString();
                if (name.equals(packUuid) || name.startsWith(packUuid + ".")) {
                    deleteTree(path);
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (Files.notExists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Same algorithm as {@link UUID#nameUUIDFromBytes}. The same title keeps the same pack. */
    static String uuid(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value;
    }

    private static String jsonString(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                + "\"";
    }

    private record Arguments(String audio, String image, String title, String titleAudio) {
        static Arguments parse(String[] args) {
            String audio = env("FREE_LUNII_AUDIO");
            String image = env("FREE_LUNII_IMAGE");
            String title = env("FREE_LUNII_TITLE");
            String titleAudio = env("FREE_LUNII_TITLE_AUDIO");
            for (int i = 0; i < args.length; i++) {
                if (args[i].isBlank()) {
                    continue;
                }
                if ("--help".equals(args[i]) || "-h".equals(args[i])) {
                    System.out.println("""
                            Import one audio file and one picture into the STUdio library.

                            pack.ps1 "stories\\My story"
                            pack.ps1 --audio FILE --image FILE --title TEXT [--title-audio FILE]

                            The story stage has no picture, so the Lunii turns the screen off while it plays.
                            """);
                    System.exit(0);
                }
                String key = args[i];
                if (i + 1 >= args.length) {
                    System.err.println("Missing value for " + key);
                    System.exit(1);
                }
                String value = args[++i];
                switch (key) {
                    case "--audio" -> audio = value;
                    case "--image" -> image = value;
                    case "--title" -> title = value;
                    case "--title-audio" -> titleAudio = value;
                    default -> {
                        System.err.println("Unknown option: " + key);
                        System.exit(1);
                    }
                }
            }
            if (audio == null || image == null || title == null || title.isBlank()) {
                System.err.println("Need --audio, --image, and --title. Or run pack.ps1 with a story folder.");
                System.exit(1);
            }
            return new Arguments(audio, image, title, titleAudio);
        }
    }

    private ImportStory() {
    }
}
