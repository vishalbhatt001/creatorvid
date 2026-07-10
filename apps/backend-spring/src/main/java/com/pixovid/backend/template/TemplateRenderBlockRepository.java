package com.pixovid.backend.template;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TemplateRenderBlockRepository extends JpaRepository<TemplateRenderBlock, String> {

  List<TemplateRenderBlock> findByRenderIdOrderByOrderAsc(String renderId);

  List<TemplateRenderBlock> findByRenderIdAndBlockId(String renderId, String blockId);

  List<TemplateRenderBlock> findByRenderIdAndPhaseNot(String renderId, RenderBlockPhase phase);

  List<TemplateRenderBlock> findByRender_IdInAndPhaseIn(List<String> renderIds, List<RenderBlockPhase> phases);
}
