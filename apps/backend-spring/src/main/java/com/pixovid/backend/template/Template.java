package com.pixovid.backend.template;

import com.pixovid.backend.common.BaseEntity;
import com.pixovid.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * An admin-authored, longer-form video composed of an ordered set of {@link TemplateBlock}s laid
 * out on a Premiere-pro style timeline, plus any number of {@link TemplateAudioClip}s. Rendering a
 * template generates every block (face-swapping the avatar onto start/end frames when enabled),
 * stitches the clips together, mixes the audio clips over them, and produces a final video + thumbnail.
 */
@Entity
@Table(name = "template")
@Getter
@Setter
public class Template extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "creatorId", nullable = false)
  private User creator;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "description")
  private String description;

  /** Number of avatar slots (1 or 2) a user must fill when rendering. */
  @Column(name = "avatarSlots", nullable = false)
  private int avatarSlots = 1;

  /** The admin's own avatars assigned to the slots (in slot order). */
  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "avatarIds", columnDefinition = "text[]", nullable = false)
  private List<String> avatarIds = new ArrayList<>();

  @Column(name = "published", nullable = false)
  private boolean published = false;

  @Column(name = "thumbnailPrompt")
  private String thumbnailPrompt;

  @Column(name = "previewVideoKey")
  private String previewVideoKey;

  @Column(name = "thumbnailKey")
  private String thumbnailKey;
}
