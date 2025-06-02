package com.kurmez.iyesi.utilities;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import okio.Source;
import java.io.IOException;

public class Progress {
    /** Arayüzde indirme ilerlemesini bildiren listener */
    public interface ProgressListener {
        /** fraction: 0.0 – 1.0 arası değeri temsil eder */
        void update(float fraction);
    }

    /** OkHttp ResponseBody’yi saran ve ilerlemeyi ProgressListener’a ileten sınıf */
    public static class ProgressResponseBody extends ResponseBody {
        private final ResponseBody responseBody;
        private final ProgressListener progressListener;
        private BufferedSource bufferedSource;

        public ProgressResponseBody(ResponseBody body, ProgressListener listener) {
            this.responseBody = body;
            this.progressListener = listener;
        }

        @Override
        public MediaType contentType() {
            return responseBody.contentType();
        }

        @Override
        public long contentLength() {
            return responseBody.contentLength();
        }

        @Override
        public BufferedSource source() {
            if (bufferedSource == null) {
                bufferedSource = Okio.buffer(wrapSource(responseBody.source()));
            }
            return bufferedSource;
        }

        private Source wrapSource(Source source) {
            return new ForwardingSource(source) {
                long totalBytesRead = 0L;

                @Override
                public long read(Buffer sink, long byteCount) throws IOException {
                    long bytesRead = super.read(sink, byteCount);
                    if (bytesRead != -1) {
                        totalBytesRead += bytesRead;
                        long fullLength = responseBody.contentLength();
                        if (fullLength > 0) {
                            float fraction = totalBytesRead / (float) fullLength;
                            progressListener.update(fraction);
                        }
                    }
                    return bytesRead;
                }
            };
        }
    }
}
