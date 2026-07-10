package com.pixovid.backend.template.render;

import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

@Builder
@Getter
public class RenderTemplateRequest {
  @Singular List<RenderBlockSpec> blocks;
  /** Indexed by avatar slot; a slot may be null (no avatar assigned). */
  List<RenderAvatarSpec> avatars;
  @Builder.Default List<RenderAudioClipSpec> audioClips = List.of();
  /** When true, the cover thumbnail is AI-generated (with the avatar as a reference). */
  boolean aiThumbnail;
  String thumbnailPrompt;
  /** When true, always re-generate every block via OpenRouter even if a baked videoKey exists. */
  boolean forceRegenerate;
  ProgressCallback onProgress;
  /** Artifacts persisted by a prior attempt, keyed by block id — enables resume. */
  Map<String, BlockResumeInfo> resume;
  ArtifactsCallback onArtifacts;
}
