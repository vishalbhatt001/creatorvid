package com.pixovid.backend.template;

import com.pixovid.backend.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Tracks one template block's progress within a render. Display fields are denormalised
 * (copied from the {@link TemplateBlock} at render time) so the progress page is stable
 * even if the admin later edits/deletes the underlying block.
 */
@Entity
@Table(name = "template_render_block")
@Getter
@Setter
public class TemplateRenderBlock extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "renderId", nullable = false)
  private TemplateRender render;

  /** The source TemplateBlock id (not a FK; the block may be edited/removed later). */
  @Column(name = "blockId", nullable = false)
  private String blockId;

  @Column(name = "order", nullable = false)
  private int order;

  @Column(name = "startSec", nullable = false)
  private double startSec;

  @Column(name = "endSec", nullable = false)
  private double endSec;

  @Column(name = "label")
  private String label;

  @Enumerated(EnumType.STRING)
  @Column(name = "phase", nullable = false)
  private RenderBlockPhase phase = RenderBlockPhase.QUEUED;

  @Column(name = "attempt", nullable = false)
  private int attempt = 0;

  @Column(name = "error")
  private String error;

  /** Set once this block's generation COMPLETES. */
  @Column(name = "videoKey")
  private String videoKey;

  /** Persisted per-render artifacts so a retry can resume instead of redoing work. */
  @Column(name = "swappedStartKey")
  private String swappedStartKey;

  @Column(name = "swappedEndKey")
  private String swappedEndKey;
}
