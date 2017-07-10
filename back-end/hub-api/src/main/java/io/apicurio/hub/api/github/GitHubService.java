/*
 * Copyright 2017 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.apicurio.hub.api.github;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.apache.commons.codec.binary.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import com.mashape.unirest.request.GetRequest;
import com.mashape.unirest.request.HttpRequest;
import com.mashape.unirest.request.HttpRequestWithBody;

import io.apicurio.hub.api.beans.ApiDesignResourceInfo;
import io.apicurio.hub.api.beans.Collaborator;
import io.apicurio.hub.api.beans.GitHubCreateFileRequest;
import io.apicurio.hub.api.beans.GitHubGetContentsResponse;
import io.apicurio.hub.api.beans.GitHubUpdateFileRequest;
import io.apicurio.hub.api.beans.ResourceContent;
import io.apicurio.hub.api.exceptions.NotFoundException;
import io.apicurio.hub.api.security.ISecurityContext;

/**
 * @author eric.wittmann@gmail.com
 */
@ApplicationScoped
public class GitHubService implements IGitHubService {

    private static Logger logger = LoggerFactory.getLogger(GitHubService.class);

    private static final String GITHUB_API_ENDPOINT = "https://api.github.com";
    private static final ObjectMapper mapper = new ObjectMapper();
    
    static {
        Unirest.setObjectMapper(new com.mashape.unirest.http.ObjectMapper() {
            public <T> T readValue(String value, Class<T> valueType) {
                try {
                    return mapper.readValue(value, valueType);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            public String writeValue(Object value) {
                try {
                    return mapper.writeValueAsString(value);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    @Inject
    private ISecurityContext security;

    /**
     * @see io.apicurio.hub.api.github.IGitHubService#validateResourceExists(java.lang.String)
     * 
     * TODO need more granular error conditions besides just {@link NotFoundException}
     */
    @Override
    public ApiDesignResourceInfo validateResourceExists(String repositoryUrl) throws NotFoundException {
        logger.debug("Validating the existence of resource {}", repositoryUrl);
        try {
            GitHubResource resource = ResourceResolver.resolve(repositoryUrl);
            if (resource == null) {
                throw new NotFoundException();
            }
            String content = getResourceContent(resource);
            Map<String, Object> jsonContent = mapper.reader(Map.class).readValue(content);
            String b64Content = (String) jsonContent.get("content");
            
            content = new String(Base64.decodeBase64(b64Content), "UTF-8");
            jsonContent = mapper.reader(Map.class).readValue(content);
            
            @SuppressWarnings("unchecked")
            Map<String, Object> infoContent = (Map<String, Object>) jsonContent.get("info");
            String name = (String) infoContent.get("title");
            String description = (String) infoContent.get("description");
            
            ApiDesignResourceInfo info = new ApiDesignResourceInfo();
            info.setName(name);
            info.setDescription(description);
            info.setUrl("https://github.com/:org/:repo/blob/master/:path"
                    .replace(":org", resource.getOrganization())
                    .replace(":repo", resource.getRepository())
                    .replace(":path", resource.getResourcePath()));
            return info;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets the content of the given GitHub resource.  This is done by querying for the
     * content using the GH API.
     * @param resource
     * 
     * TODO need more granular error conditions besides just {@link NotFoundException}
     */
    private String getResourceContent(GitHubResource resource) throws NotFoundException {
        logger.debug("Getting resource content for: {}/{} - {}", 
                resource.getOrganization(), resource.getRepository(), resource.getResourcePath());
        try {
            String contentUrl = this.endpoint("/repos/:owner/:repo/contents/:path")
                    .bind("owner", resource.getOrganization())
                    .bind("repo", resource.getRepository())
                    .bind("path", resource.getResourcePath())
                    .url();
            GetRequest request = Unirest.get(contentUrl).header("Accept", "application/json");
            security.addSecurity(request);
            HttpResponse<String> userResp = request.asString();
            if (userResp.getStatus() != 200) {
                throw new NotFoundException();
            } else {
                String json = userResp.getBody();
                return json;
            }
        } catch (UnirestException e) {
            throw new NotFoundException();
        }
    }
    
    /**
     * @see io.apicurio.hub.api.github.IGitHubService#getCollaborators(java.lang.String)
     */
    @Override
    public Collection<Collaborator> getCollaborators(String repositoryUrl) throws NotFoundException {
        logger.debug("Getting collaborator information for repository url: {}", repositoryUrl);
        try {
            GitHubResource resource = ResourceResolver.resolve(repositoryUrl);
            if (resource == null) {
                throw new NotFoundException();
            }
            
            String commitsUrl = endpoint("/repos/:org/:repo/commits")
                    .bind("org", resource.getOrganization())
                    .bind("repo", resource.getRepository())
                    .url();
            HttpRequest request = Unirest.get(commitsUrl).header("Accept", "application/json")
                    .queryString("path", resource.getResourcePath());
            security.addSecurity(request);
            HttpResponse<JsonNode> response = request.asJson();
            if (response.getStatus() != 200) {
                throw new UnirestException("Unexpected response from GitHub: " + response.getStatus() + "::" + response.getStatusText());
            }
            
            Map<String, Collaborator> cidx = new HashMap<>();
            JsonNode node = response.getBody();
            if (node.isArray()) {
                JSONArray array = node.getArray();
                array.forEach( obj -> {
                    JSONObject jobj = (JSONObject) obj;
                    JSONObject authorObj = (JSONObject) jobj.get("author");
                    String user = authorObj.getString("login");
                    Collaborator collaborator = cidx.get(user);
                    if (collaborator == null) {
                        collaborator = new Collaborator();
                        collaborator.setName(user);
                        collaborator.setUrl(authorObj.getString("html_url"));
                        collaborator.setCommits(1);
                        cidx.put(user, collaborator);
                    } else {
                        collaborator.setCommits(collaborator.getCommits() + 1);
                    }
                });
            }
            return cidx.values();
        } catch (UnirestException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @see io.apicurio.hub.api.github.IGitHubService#getResourceContent(java.lang.String)
     */
    @Override
    public ResourceContent getResourceContent(String repositoryUrl) throws NotFoundException {
        try {
            GitHubResource resource = ResourceResolver.resolve(repositoryUrl);
            String getContentUrl = this.endpoint("/repos/:org/:repo/contents/:path")
                    .bind("org", resource.getOrganization())
                    .bind("repo", resource.getRepository())
                    .bind("path", resource.getResourcePath())
                    .url();
            HttpRequest request = Unirest.get(getContentUrl).header("Accept", "application/json");
            security.addSecurity(request);
            HttpResponse<GitHubGetContentsResponse> response = request.asObject(GitHubGetContentsResponse.class);
            if (response.getStatus() != 200) {
                throw new UnirestException("Unexpected response from GitHub: " + response.getStatus() + "::" + response.getStatusText());
            }
            
            GitHubGetContentsResponse body = response.getBody();
            String b64Content = body.getContent();
            String content = new String(Base64.decodeBase64(b64Content), "utf-8");
            ResourceContent rval = new ResourceContent();
            rval.setContent(content);
            rval.setSha(body.getSha());
            return rval;
        } catch (UnirestException | UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }
    
    /**
     * @see io.apicurio.hub.api.github.IGitHubService#updateResourceContent(java.lang.String, java.lang.String, io.apicurio.hub.api.beans.ResourceContent)
     */
    @Override
    public void updateResourceContent(String repositoryUrl, String commitMessage, ResourceContent content) {
        try {
            String b64Content = Base64.encodeBase64String(content.getContent().getBytes("utf-8"));
            
            GitHubUpdateFileRequest requestBody = new GitHubUpdateFileRequest();
            requestBody.setMessage(commitMessage);
            requestBody.setContent(b64Content);
            requestBody.setSha(content.getSha());

            GitHubResource resource = ResourceResolver.resolve(repositoryUrl);
            String createContentUrl = this.endpoint("/repos/:org/:repo/contents/:path")
                .bind("org", resource.getOrganization())
                .bind("repo", resource.getRepository())
                .bind("path", resource.getResourcePath())
                .url();

            HttpRequestWithBody request = Unirest.put(createContentUrl).header("Content-Type", "application/json; charset=utf-8");
            security.addSecurity(request);
            HttpResponse<InputStream> response = request.body(requestBody).asBinary();
            if (response.getStatus() != 200) {
                throw new UnirestException("Unexpected response from GitHub: " + response.getStatus() + "::" + response.getStatusText());
            }
        } catch (UnsupportedEncodingException | UnirestException e) {
            throw new RuntimeException(e);
        }
    }
    
    /**
     * @see io.apicurio.hub.api.github.IGitHubService#createResourceContent(java.lang.String, java.lang.String, java.lang.String)
     */
    @Override
    public void createResourceContent(String repositoryUrl, String commitMessage, String content) {
        try {
            String b64Content = Base64.encodeBase64String(content.getBytes("utf-8"));
            
            GitHubCreateFileRequest requestBody = new GitHubCreateFileRequest();
            requestBody.setMessage(commitMessage);
            requestBody.setContent(b64Content);

            GitHubResource resource = ResourceResolver.resolve(repositoryUrl);
            String createContentUrl = this.endpoint("/repos/:org/:repo/contents/:path")
                .bind("org", resource.getOrganization())
                .bind("repo", resource.getRepository())
                .bind("path", resource.getResourcePath())
                .url();

            HttpRequestWithBody request = Unirest.put(createContentUrl).header("Content-Type", "application/json; charset=utf-8");
            security.addSecurity(request);
            HttpResponse<InputStream> response = request.body(requestBody).asBinary();
            if (response.getStatus() != 201) {
                throw new UnirestException("Unexpected response from GitHub: " + response.getStatus() + "::" + response.getStatusText());
            }
        } catch (UnsupportedEncodingException | UnirestException e) {
            throw new RuntimeException(e);
        }
    }
    
    /**
     * @see io.apicurio.hub.api.github.IGitHubService#getOrganizations()
     */
    @Override
    public Collection<String> getOrganizations() {
        logger.debug("Getting organizations for current user.");
        try {
            String orgsUrl = endpoint("/user/orgs").url();
            Collection<String> rval = new HashSet<>();
            while (orgsUrl != null) {
                HttpRequest request = Unirest.get(orgsUrl).header("Accept", "application/json");
                security.addSecurity(request);
                HttpResponse<JsonNode> response = request.asJson();
                if (response.getStatus() != 200) {
                    throw new UnirestException("Unexpected response from GitHub: " + response.getStatus() + "::" + response.getStatusText());
                }
                
                JSONArray array = response.getBody().getArray();
                array.forEach( obj -> {
                    JSONObject org = (JSONObject) obj;
                    String login = org.getString("login");
                    rval.add(login);
                });
                
                String linkHeader = response.getHeaders().getFirst("Link");
                Map<String, String> links = parseLinkHeader(linkHeader);
                orgsUrl = links.get("next");
            }
            return rval;
        } catch (UnirestException e) {
            throw new RuntimeException(e);
        }
    }
    
    /**
     * @see io.apicurio.hub.api.github.IGitHubService#getRepositories(java.lang.String)
     */
    @Override
    public Collection<String> getRepositories(String org) {
        logger.debug("Getting the repositories from organization {}", org);
        try {
            String reposUrl;
            if (org.equals(this.security.getCurrentUser().getLogin())) {
                reposUrl = endpoint("/users/:username/repos").bind("username", org).url();
            } else {
                reposUrl = endpoint("/orgs/:org/repos").bind("org", org).url();
            }
            
            Collection<String> rval = new HashSet<>();
            while (reposUrl != null) {
                HttpRequest request = Unirest.get(reposUrl).header("Accept", "application/json");
                security.addSecurity(request);
                HttpResponse<JsonNode> response = request.asJson();
                if (response.getStatus() != 200) {
                    throw new UnirestException("Unexpected response from GitHub: " + response.getStatus() + "::" + response.getStatusText());
                }
                
                JSONArray array = response.getBody().getArray();
                array.forEach( obj -> {
                    JSONObject repo = (JSONObject) obj;
                    String name = repo.getString("name");
                    rval.add(name);
                });
                
                String linkHeader = response.getHeaders().getFirst("Link");
                Map<String, String> links = parseLinkHeader(linkHeader);
                reposUrl = links.get("next");
            }
            return rval;
        } catch (UnirestException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Parses the HTTP "Link" header and returns a map of named links.  A typical link header value
     * might look like this:
     * 
     * <https://api.github.com/user/1890703/repos?page=2>; rel="next", <https://api.github.com/user/1890703/repos?page=3>; rel="last"
     * 
     * The return value for this would be a map with two items:
     *   
     *   next=https://api.github.com/user/1890703/repos?page=2
     *   last=https://api.github.com/user/1890703/repos?page=3
     *   
     * @param linkHeader
     */
    static Map<String, String> parseLinkHeader(String linkHeader) {
        Map<String, String> rval = new HashMap<>();
        if (linkHeader != null) {
            String[] split = linkHeader.split(",");
            for (String item : split) {
                Pattern pattern = Pattern.compile("<(.+)>; rel=\"(.+)\"");
                Matcher matcher = pattern.matcher(item.trim());
                if (matcher.matches()) {
                    String url = matcher.group(1);
                    String name = matcher.group(2);
                    rval.put(name, url);
                }
            }
        }
        return rval;
    }

    /**
     * Creates a github API endpoint from the api path.
     * @param path
     */
    protected Endpoint endpoint(String path) {
        return new Endpoint(GITHUB_API_ENDPOINT + path);
    }
    
    /**
     * An endpoint that will be used to make a call to the GitHub API.  The form of an endpoint path
     * should be (for example):
     * 
     * https://api.github.com/repos/:owner/:repo/contents/:path
     * 
     * The path parameters can then be set by calling bind() on the {@link Endpoint} object.
     * 
     * @author eric.wittmann@gmail.com
     */
    public static class Endpoint {
        
        private String url;
        
        /**
         * Constructor.
         */
        public Endpoint(String url) {
            this.url = url;
        }
        
        /**
         * Binds a parameter to the endpoint.  
         * @param paramName
         * @param value
         * @return
         */
        public Endpoint bind(String paramName, Object value) {
            this.url = this.url.replace(":" + paramName, String.valueOf(value));
            return this;
        }
        
        /**
         * Returns the url.
         */
        public String url() {
            return this.url;
        }
        
        /**
         * @see java.lang.Object#toString()
         */
        @Override
        public String toString() {
            return this.url;
        }
        
    }

}