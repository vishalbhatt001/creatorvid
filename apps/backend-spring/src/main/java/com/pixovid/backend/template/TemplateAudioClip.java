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

/**
 * An audio clip on the template timeline: a position (startSec/endSec), an audio lane
 * (track), and a Premiere-style crop window into its full {@code duration}.
 */
@Entity
@Table(name = "template_audio_clip")
@Getter
@Setter
public class TemplateAudioClip extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "templateId", nullable = false)
  private Template template;

  @Column(name = "order", nullable = false)
  private int order = 0;

  @Column(name = "startSec", nullable = false)
  private double startSec;

  @Column(name = "endSec", nullable = false)
  private double endSec;

  @Column(name = "track", nullable = false)
  private int track = 0;

  @Column(name = "audioKey", nullable = false)
  private String audioKey;

  @Column(name = "name")
  private String name;

  /** Full uploaded length (seconds). */
  @Column(name = "duration", nullable = false)
  private double duration;

  @Column(name = "cropStart", nullable = false)
  private double cropStart = 0;

  @Column(name = "cropEnd")
  private Double cropEnd;
}
