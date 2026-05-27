package com.runespeak.translate;

import lombok.Value;

@Value
public class DownloadFile {
    String remotePath;
    String localFilename;

    public String getDownloadUrl(String modelId) {
        String path = remotePath.isEmpty()
                ? localFilename
                : remotePath + "/" + localFilename;
        return String.format("https://huggingface.co/%s/resolve/main/%s", modelId, path);
    }
}
