package com.pixovid.backend.avatar;

import com.pixovid.backend.common.BaseEntity;
import com.pixovid.backend.generation.GenerationStatus;
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

/** A user's likeness, built from 1-2 photos: the FaceFusion swap source and an OpenRouter reference image. */
@Entity
@Table(name = "avatar")
@Getter
@Setter
public class Avatar extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "userId", nullable = false)
  private User user;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private GenerationStatus status = GenerationStatus.COMPLETED;

  @Column(name = "name", nullable = false)
  private String name;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "sourceImageKeys", columnDefinition = "text[]", nullable = false)
  private List<String> sourceImageKeys = new ArrayList<>();

  /** Primary face image used for swaps + references; defaults to the first source photo. */
  @Column(name = "faceKey")
  private String faceKey;

  @Column(name = "error")
  private String error;
}
