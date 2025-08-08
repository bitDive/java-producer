package io.bitdive.parent.safety_config;

import io.bitdive.parent.parserConfig.YamlParserConfig;


import javax.net.ssl.*;
import java.net.URL;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

public class SSLContextCustomBitDive {

    // ---------- контексты ----------
    private static volatile SSLContext strictCtx;
    private static volatile SSLContext trustAllCtx;

    /**
     * Строгий контекст с цепочкой сервера
     */
    public static SSLContext strict() {
        if (strictCtx == null) {
            synchronized (SSLContextCustomBitDive.class) {
                if (strictCtx == null) {
                    strictCtx = createStrictContext();
                }
            }
        }
        return strictCtx;
    }

    /**
     * Контекст, отключающий все проверки – использовать ТОЛЬКО как fallback
     */
    public static SSLContext trustAll() {
        if (trustAllCtx == null) {
            synchronized (SSLContextCustomBitDive.class) {
                if (trustAllCtx == null) {
                    trustAllCtx = createTrustAllContext();
                }
            }
        }
        return trustAllCtx;
    }

    // ---- internal ----
    private static SSLContext createStrictContext() {
        try {
            URL url = new URL(YamlParserConfig.getProfilingConfig()
                    .getMonitoring()
                    .getSendFiles()
                    .getServerConsumer()
                    .getUrl());
            Certificate[] chain = fetchServerChain(url.getHost(), 443);
            return buildSslContextWithChain(chain);
        } catch (Exception e) {
            throw new IllegalStateException("Can't build strict SSLContext", e);
        }
    }

    private static SSLContext buildSslContextWithChain(Certificate[] chain) throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);

        // добавляем все сертификаты из цепочки
        for (int i = 0; i < chain.length; i++) {
            ks.setCertificateEntry("cert-" + i, chain[i]);
        }

        TrustManagerFactory tmf =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), new SecureRandom());
        return ctx;
    }

    private static Certificate[] fetchServerChain(String host, int port) throws Exception {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        try (SSLSocket socket = (SSLSocket) factory.createSocket(host, port)) {
            socket.startHandshake();
            return socket.getSession().getPeerCertificates();  // leaf + intermediates
        }
    }

    private static SSLContext createTrustAllContext() {
        try {
            TrustManager[] trustAll = {new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {
                }

                public void checkServerTrusted(X509Certificate[] c, String a) {
                }

                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("Can't build trust-all SSLContext", e);
        }
    }
}

