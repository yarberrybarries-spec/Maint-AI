package ai.weixiu.mapper;

import ai.weixiu.entity.TaskAutoReview;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TaskAutoReviewMapper extends BaseMapper<TaskAutoReview> {
    @Select("SELECT * FROM task_auto_review WHERE task_id = #{taskId} AND evidence_version = #{version} FOR UPDATE")
    TaskAutoReview selectByTaskVersionForUpdate(@Param("taskId") Long taskId, @Param("version") Integer version);
}
