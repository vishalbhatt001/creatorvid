package com.pixovid.backend.template;

import com.pixovid.backend.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "template_block")
@Getter
@Setter
public class TemplateBlock extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "templateId", nullable = false)
  private Template template;

  /** Position on the timeline (seconds) and rendering order. */
  @Column(name = "order", nullable = false)
  private int order;

  @Column(name = "startSec", nullable = false)
  private double startSec;

  @Column(name = "endSec", nullable = false)
  private double endSec;

  /** Video track (lane); higher tracks render on top where blocks overlap in time. */
  @Column(name = "track", nullable = false)
  private int track = 0;

  @Column(name = "prompt", nullable = false)
  private String prompt;

  @Column(name = "model", nullable = false)
  private String model;

  @Column(name = "duration")
  private Integer duration;

  @Column(name = "resolution")
  private String resolution;

  @Column(name = "aspectRatio")
  private String aspectRatio;

  @Column(name = "startImageKey")
  private String startImageKey;

  @Column(name = "endImageKey")
  private String endImageKey;

  /** Cached "approved" face-swap previews of the start/end frames. */
  @Column(name = "swappedStartKey")
  private String swappedStartKey;

  @Column(name = "swappedEndKey")
  private String swappedEndKey;

  /** This block's individually "baked" preview clip. */
  @Column(name = "videoKey")
  private String videoKey;

  /** An admin-uploaded "raw" video; when set, this block is NOT AI-generated. */
  @Column(name = "sourceVideoKey")
  private String sourceVideoKey;

  /** Premiere-style crop window into the generated/uploaded clip. */
  @Column(name = "cropStart", nullable = false)
  private double cropStart = 0;

  @Column(name = "cropEnd")
  private Double cropEnd;

  /** Copy/paste link: blocks sharing a linkGroupId share generation content. */
  @Column(name = "linkGroupId")
  private String linkGroupId;

  @Column(name = "faceSwapStart", nullable = false)
  private boolean faceSwapStart = false;

  @Column(name = "faceSwapEnd", nullable = false)
  private boolean faceSwapEnd = false;

  /** Which template avatar slot (0-based) this block uses. */
  @Column(name = "avatarSlot", nullable = false)
  private int avatarSlot = 0;

  /** Guidance for the diffusion swap provider (SWAP_PROVIDER=flux); ignored by FaceFusion. */
  @Column(name = "swapContext")
  private String swapContext;

  /** When true, the audio under this block is sent as an audio reference so the subject lip-syncs to it. */
  @Column(name = "lipsync", nullable = false)
  private boolean lipsync = false;

  /** "facefusion" or an OpenRouter image model id; null falls back to the server default. */
  @Column(name = "swapModel")
  private String swapModel;
}
