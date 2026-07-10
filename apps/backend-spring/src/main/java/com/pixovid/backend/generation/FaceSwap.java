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
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "face_swap")
@Getter
@Setter
public class FaceSwap extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "userId", nullable = false)
  private User user;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private GenerationStatus status = GenerationStatus.PENDING;

  /** The face to apply. */
  @Column(name = "sourceKey", nullable = false)
  private String sourceKey;

  /** The base image being modified. */
  @Column(name = "targetKey", nullable = false)
  private String targetKey;

  @Column(name = "outputKey")
  private String outputKey;

  @Column(name = "error")
  private String error;
}
