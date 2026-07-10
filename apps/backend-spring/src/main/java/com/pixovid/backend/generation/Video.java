package com.pixovid.backend.generation;

import com.pixovid.backend.common.BaseEntity;
import com.pixovid.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "video")
@Getter
@Setter
public class Video extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "userId", nullable = false)
  private User user;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private GenerationStatus status = GenerationStatus.PENDING;

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

  @Column(name = "generateAudio")
  private Boolean generateAudio;

  @Column(name = "startFrameKey")
  private String startFrameKey;

  @Column(name = "endFrameKey")
  private String endFrameKey;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "referenceFrameKeys", columnDefinition = "text[]", nullable = false)
  private List<String> referenceFrameKeys = new ArrayList<>();

  @Column(name = "videoKey")
  private String videoKey;

  @Column(name = "providerJobId")
  private String providerJobId;

  @Column(name = "cost")
  private Double cost;

  @Column(name = "error")
  private String error;
}
