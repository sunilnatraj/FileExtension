package org.openrefine.extensions.files.importer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.refine.ProjectMetadata;
import com.google.refine.importers.ImportingParserBase;
import com.google.refine.importers.SeparatorBasedImporter;
import com.google.refine.importing.ImportingJob;
import com.google.refine.model.Project;
import com.google.refine.util.JSONUtilities;
import com.google.refine.util.ParsingUtilities;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;

import static java.nio.charset.StandardCharsets.UTF_8;

public class FilesImporter {
    private static final Logger logger = LoggerFactory.getLogger("FilesImporter");
    private static final int fileContentSizeLimit = 1024;

    public static long generateFileList(File file, ObjectNode options) throws IOException {
        JsonNode directoryInput = options.get("directoryJsonValue");

        for (JsonNode directoryPath : directoryInput) {
            getFileList(directoryPath.get("directory").asText(), file);
        }
        return file.length();
    }

    public static void loadData(Project project, ProjectMetadata metadata, ImportingJob job, ArrayNode fileRecords) throws Exception {
        ObjectNode options = ParsingUtilities.mapper.createObjectNode();
        JSONUtilities.safePut(options, "includeArchiveFileName", true);
        JSONUtilities.safePut(options, "includeFileSources", false);
        ArrayNode columns = ParsingUtilities.mapper.createArrayNode();
        columns.add("fileName");
        columns.add("fileSize(KB)");
        columns.add("fileExtension");
        columns.add("lastModifiedTime");
        columns.add("creationTime");
        columns.add("author");
        columns.add("filePath");
        columns.add("filePermissions");
        columns.add("sha256");
        columns.add("fileContent");
        JSONUtilities.safePut(options, "columnNames", columns);
        JSONUtilities.safePut(options, "separator", ",");


        ImportingParserBase parser = new SeparatorBasedImporter();
        List<Exception> exceptions = new ArrayList<Exception>();

        parser.parse(
                project,
                metadata,
                job,
                JSONUtilities.getObjectList(fileRecords),
                "csv",
                -1,
                options,
                exceptions);

        if(exceptions.size() > 0) {
            throw new Exception("Failed to process file list");
        }
        project.update();
    }

    private static void getFileList(String directoryPath, File file) throws IOException {
        int depth = 1;
        try {
                FileWriter writer = new FileWriter(file);
                CSVPrinter csvPrinter = new CSVPrinter(writer, CSVFormat.DEFAULT);

                Path rootPath = Paths.get(directoryPath);
                Files.walkFileTree(rootPath, EnumSet.noneOf(FileVisitOption.class), depth, new SimpleFileVisitor<Path>() {

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String fileRecord = "";
                    try {
                        if (!attrs.isDirectory()) {
                            String fileName = file.getFileName().toString();
                            String filePath = file.toAbsolutePath().toString();
                            String author = "";
                            try {
                                author = Files.getOwner(file).getName(); // File owner (may not always be available)
                            } catch (Exception e) {
                                // ignore
                            }
                            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                            String dateCreated = sdf.format(attrs.creationTime().toMillis());
                            String dateModified = sdf.format(attrs.lastModifiedTime().toMillis());
                            long fileSize = (long) Math.ceil(attrs.size() / 1024.0);
                            String fileExt = getFileExt(fileName);
                            String filePermissions = getFilePermissions(file);
                            String fileChecksum = calculateFileChecksum(file, "SHA-256");
                            String fileContent = getFileContent(file);

                            csvPrinter.printRecord(fileName, fileSize, fileExt, dateModified, dateCreated, author, filePath, filePermissions, fileChecksum, fileContent);
                        }
                    } catch (Exception e) {
                        logger.info("--- importDirectory. Error processing file: " + file + " - " + e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            csvPrinter.flush();
            writer.close();
        } catch (Exception e) {
            logger.info("--- importDirectory. Error reading directory: " + e.getMessage());
            throw e;
        }
    }

    private static String getFileExt(String fileName) {
        String fileExt = "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < fileName.length() - 1) {
            fileExt = fileName.substring(dotIndex + 1);
        }
        return fileExt;
    }

    private static String getFilePermissions(Path path) {
        String filePermissions = "";
        try {
            if (Files.exists(path)) {
                FileStore store = Files.getFileStore(path);
                if (!store.supportsFileAttributeView(PosixFileAttributeView.class)) {
                    logger.info("--- importDirectory. POSIX file attributes are not supported on this system.");
                }
                else {
                    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
                    filePermissions = PosixFilePermissions.toString(permissions);
                }
            }
        } catch (Exception e) {
            logger.info("--- importDirectory. Failed to retrieve file permissions: " + e.getMessage());
        }
        return filePermissions;
    }

    private static String calculateFileChecksum(Path path, String algorithm) throws Exception {
        if (Files.exists(path)) {
            try (var fileChannel = FileChannel.open(path, StandardOpenOption.READ);
                 var lock = fileChannel.lock(0, Long.MAX_VALUE, true)) {
                    MessageDigest digest = MessageDigest.getInstance(algorithm);
                    try (var inputStream = Files.newInputStream(path)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = inputStream.read(buffer)) != -1) {
                            digest.update(buffer, 0, bytesRead);
                        }
                    }
                    return bytesToHex(digest.digest());
            }
        }
        return "";
    }

    private static String bytesToHex(byte[] bytes) {
        try (Formatter formatter = new Formatter()) {
            for (byte b : bytes) {
                formatter.format("%02x", b);
            }
            return formatter.toString();
        }
    }

    private static String getFileContent(Path path) {
        if (Files.exists(path)) {
            try {
                String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                int maxBytes = Math.min(content.length(), 1 * fileContentSizeLimit); // Max 32KB
                if ( canIncludeFileContent(content)) {
                    return content.substring(0, maxBytes);
                }
            }
            catch (IOException e) {
                logger.info("--- importDirectory. Failed to read file content: " + e.getMessage());
            }
        }
        return "";
    }

    private static boolean canIncludeFileContent(String content) {
            int lengthToCheck = Math.min(content.length(), fileContentSizeLimit);
            int nonPrintableCount = 0;
            for (int i = 0; i < lengthToCheck; i++) {
               if ( !Character.isDefined(content.charAt(i)) ||
                       (!(content.charAt(i) == '\r' || content.charAt(i) == '\n' || content.charAt(i) == '\t') &&
                        Character.isISOControl(content.charAt(i))) )
                {
                    nonPrintableCount++;
                }
            }
            return (nonPrintableCount / (double) lengthToCheck) <= 0.05;
    }

}
