package com.intellidesk.agent.entity;

import com.intellidesk.category.entity.Category;
import com.intellidesk.common.domain.BaseEntity;
import com.intellidesk.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * "Agent X knows category Y" - the expertise graph used by the smart
 * assignment algorithm (Phase 5) to score candidate agents.
 */
@Entity
@Table(
        name = "agent_skills",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_agent_skills_agent_category",
                columnNames = {"agent_id", "category_id"}
        ),
        indexes = @Index(name = "idx_agent_skills_category", columnList = "category_id")
)
public class AgentSkill extends BaseEntity {

    @ManyToOne(optional = false)
    @JoinColumn(name = "agent_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_agent_skills_agent"))
    private User agent;

    @ManyToOne(optional = false)
    @JoinColumn(name = "category_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_agent_skills_category"))
    private Category category;

    /** Manual expertise weight in [1..5]; lets admins fine-tune the matcher. */
    @Column(name = "proficiency_level", nullable = false)
    private int proficiencyLevel = 3;

    protected AgentSkill() {
        // Required by JPA
    }

    public AgentSkill(User agent, Category category, int proficiencyLevel) {
        this.agent = agent;
        this.category = category;
        this.proficiencyLevel = proficiencyLevel;
    }

    public User getAgent() {
        return agent;
    }

    public Category getCategory() {
        return category;
    }

    public int getProficiencyLevel() {
        return proficiencyLevel;
    }

    public void setProficiencyLevel(int proficiencyLevel) {
        this.proficiencyLevel = proficiencyLevel;
    }
}
