package myraft.util.util;

import myraft.exception.MyRaftException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class MyRaftFileUtil {

    /**
     * 基于文件名，读取整个文件 (不考虑大文件内存不够的问题)
     * */
    public static String getFileContent(File file){
        byte[] bytes = new byte[1024];

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(1024);
        try(FileInputStream fileInputStream = new FileInputStream(file)) {
            int actualRead;
            while((actualRead = fileInputStream.read(bytes)) != -1){
                byteArrayOutputStream.write(bytes,0,actualRead);
            }

            return byteArrayOutputStream.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            throw new MyRaftException("FileUtil.getFileContent error",e);
        }
    }

    public static void createFile(File file){
        if(file.exists()){
            return;
        }

        file.getParentFile().mkdirs();

        try {
            file.createNewFile();
        } catch (IOException e) {
            throw new MyRaftException("createNewFile error!" + file,e);
        }
    }

    /**
     * content内容写入目标文件
     * */
    public static void writeInFile(File targetFile, String content){
        try(BufferedWriter bufferedWriter = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(targetFile.toPath())))){
            bufferedWriter.write(content);
        }catch (IOException e){
            throw new MyRaftException("FileUtil.writeInFile error",e);
        }
    }
}
