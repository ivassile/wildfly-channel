/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2020, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.wildfly.channel;

import static java.util.Objects.requireNonNull;
import static java.util.Optional.empty;
import static org.wildfly.channel.version.VersionMatcher.COMPARATOR;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.wildfly.channel.spi.MavenVersionsResolver;

public class ChannelSession implements AutoCloseable {
    private List<Channel> channels;
    private final ChannelRecorder recorder = new ChannelRecorder();

    public ChannelSession(List<Channel> channels, MavenVersionsResolver.Factory factory) {
        requireNonNull(channels);
        requireNonNull(factory);
        this.channels = channels;
        for (Channel channel : channels) {
            channel.initResolver(factory);
        }
    }

    public MavenArtifact resolveMavenArtifact(String groupId, String artifactId, String extension, String classifier, String baseVersion) throws UnresolvedMavenArtifactException {
        requireNonNull(groupId);
        requireNonNull(artifactId);

        // find all latest versions from the different channels;
        Map<String, Channel> found = new HashMap<>();
        for (Channel channel : channels) {
            Optional<Channel.ResolveLatestVersionResult> result = channel.resolveLatestVersion(groupId, artifactId, extension, classifier, baseVersion);
            if (result.isPresent()) {
                found.put(result.get().version, result.get().channel);
            }
        }

        if (found.isEmpty()) {
            if (baseVersion != null) {
                // resolve against the base version
                for (Channel channel : channels) {
                    try {
                        Channel.ResolveArtifactResult artifact = channel.resolveArtifact(groupId, artifactId, extension, classifier, baseVersion);
                        recorder.recordStream(groupId, artifactId, baseVersion, channel);
                        return new MavenArtifact(groupId, artifactId, extension, classifier, baseVersion, artifact.file);
                    } catch (UnresolvedMavenArtifactException e) {
                    }
                }
            }
            throw new UnresolvedMavenArtifactException(String.format("Can not resolve Maven artifact (no stream found) : %s:%s:%s:%s:%s", groupId, artifactId, extension, classifier, baseVersion));
        }

        // compare all latest version from the channels to find the latest overall
        Optional<String> result = found.keySet().stream()
                .sorted(COMPARATOR.reversed())
                .findFirst();
        if (result.isPresent()) {
            String latestVersion = result.get();
            Channel channel = found.get(latestVersion);
            Channel.ResolveArtifactResult artifact = channel.resolveArtifact(groupId, artifactId, extension, classifier, latestVersion);
            recorder.recordStream(groupId, artifactId, latestVersion, channel);
            return new MavenArtifact(groupId, artifactId, extension, classifier, latestVersion, artifact.file);
        }

        throw new UnresolvedMavenArtifactException(String.format("Can not resolve Maven artifact : %s:%s:%s:%s:%s", groupId, artifactId, extension, classifier, baseVersion));
    }

    @Override
    public void close()  {
        for (Channel channel : channels) {
            channel.close();
        }
    }

    public List<Channel> getRecordedChannels() {
        return recorder.getRecordedChannels();
    }
}
