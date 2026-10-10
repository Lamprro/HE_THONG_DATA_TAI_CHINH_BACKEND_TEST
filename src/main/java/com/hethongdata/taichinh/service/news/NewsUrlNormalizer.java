package com.hethongdata.taichinh.service.news;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class NewsUrlNormalizer {
    private NewsUrlNormalizer() {}
    public static String normalize(String value) {
        URI uri=URI.create(value.trim()).normalize();
        String scheme=uri.getScheme()==null?"":uri.getScheme().toLowerCase(Locale.ROOT);
        if(!Set.of("http","https").contains(scheme)||uri.getHost()==null||uri.getUserInfo()!=null)
            throw new IllegalArgumentException("Invalid news URL");
        String host=uri.getHost().toLowerCase(Locale.ROOT);
        int port=uri.getPort();
        String authority=host+((port<0||(port==80&&scheme.equals("http"))||(port==443&&scheme.equals("https")))?"":":"+port);
        List<String> query=new ArrayList<>();
        if(uri.getRawQuery()!=null) for(String part:uri.getRawQuery().split("&")) {
            String key=URLDecoder.decode(part.split("=",2)[0],StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            if(!key.startsWith("utm_")&&!Set.of("fbclid","gclid","zarsrc").contains(key)) query.add(part);
        }
        String path=uri.getRawPath()==null||uri.getRawPath().isEmpty()?"/":uri.getRawPath();
        return scheme+"://"+authority+path+(query.isEmpty()?"":"?"+String.join("&",query));
    }
}
