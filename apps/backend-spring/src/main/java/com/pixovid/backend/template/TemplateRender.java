package com.pixovid.backend.template;

import com.pixovid.backend.avatar.Avatar;
import com.pixovid.backend.common.BaseEntity;
import com.pixovid.backend.generation.GenerationStatus;
import com.pixovid.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "template_render")
@Getter
@Setter
public class TemplateRender extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "templateId", nullable = false)
  private Template template;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "userId", nullable = false)
  private User user;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private GenerationStatus status = GenerationStatus.PENDING;

  /** The avatars filling the template's slots, in slot order (denormalized string ids). */
  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "avatarIds", columnDefinition = "text[]", nullable = false)
  private List<String> avatarIds = new ArrayList<>();

  @Column(name = "videoKey")
  private String videoKey;

  @Column(name = "thumbnailKey")
  private String thumbnailKey;

  @Column(name = "cost")
  private Double cost;

  @Column(name = "error")
  private String error;

  /** Avatars referenced by this render, so they can't be deleted out from under it. */
  @ManyToMany
  @JoinTable(
      name = "_AvatarToTemplateRender",
      joinColumns = @JoinColumn(name = "B"),
      inverseJoinColumns = @JoinColumn(name = "A"))
  private Set<Avatar> avatars = new HashSet<>();

  /** Per-block progress, shown on the live /generation/:id page. */
  @OneToMany(mappedBy = "render", fetch = FetchType.LAZY)
  @OrderBy("order ASC")
  private List<TemplateRenderBlock> blocks = new ArrayList<>();
}
