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
@Table(name = "image")
@Getter
@Setter
public class Image extends BaseEntity {

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

  @Column(name = "resolution")
  private String resolution;

  @Column(name = "aspectRatio")
  private String aspectRatio;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "referenceImageKeys", columnDefinition = "text[]", nullable = false)
  private List<String> referenceImageKeys = new ArrayList<>();

  @Column(name = "imageKey")
  private String imageKey;

  @Column(name = "providerJobId")
  private String providerJobId;

  @Column(name = "cost")
  private Double cost;

  @Column(name = "error")
  private String error;
}
